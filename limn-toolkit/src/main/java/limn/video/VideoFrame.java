package limn.video;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One decoded picture, borrowed from the producer that published it. A frame is a <em>lease</em>,
 * not a value: after {@link #release()} the same instance is refilled with a different picture, so
 * nothing here may be cached, stored in a collection, or compared for equality: hold it, use it,
 * release it.
 *
 * <p>Deliberately mutable and deliberately reused. At 60 pictures a second, allocating planes,
 * buffers or wrapper objects per picture is a garbage collection pause every few seconds;
 * publishing a frame writes primitives into an object that already exists and allocates nothing.
 * For the same reason a frame is never an immutable bitmap: a renderer that caches an uploaded
 * texture by object identity would show whichever picture happened to be in the slot first, for
 * the life of the stream.
 *
 * <p>Not {@link AutoCloseable}: closing is expected to be idempotent, whereas releasing a frame
 * twice returns one slot to the pool twice and must be loud. See {@link #release()}.
 *
 * <p><b>A picture has two possible shapes and {@link #kind()} says which.</b> Either it is planar
 * samples a consumer can read, or it is an opaque handle to memory a device owns and a consumer
 * cannot address at all, which is what a hardware decoder hands back. The two are not
 * interchangeable and neither is a special case of the other: {@link #plane(int)} fails on a
 * handle rather than returning something plausible, {@link #handle()} fails on planar samples, and
 * {@link #toPlanar()} is how a consumer that cannot use a handle asks for one to be read back into
 * memory it can.
 */
public final class VideoFrame {

    /** What {@link #ptsMicros()} reports when the producer does not know the presentation time. */
    public static final long PTS_UNKNOWN = Long.MIN_VALUE;

    /**
     * Which of the two shapes a picture has: samples in memory, or a handle to a device allocation.
     *
     * <p>The constants beyond {@link #PLANAR} name a <em>family of handle</em> and not a type. The
     * toolkit depends on nothing and cannot see a {@code CVPixelBufferRef}, an
     * {@code ID3D11Texture2D*} or a dma-buf descriptor; what it carries is a {@code long} whose
     * meaning is this enum, so that a backend can bind the ones it knows how to bind and
     * <em>refuse</em> the rest by name rather than binding a handle it has misread.
     */
    public enum Kind {

        /** Samples a consumer can read. {@link #plane(int)} answers and {@link #handle()} throws. */
        PLANAR,

        /**
         * A macOS {@code IOSurfaceRef}, which is what a VideoToolbox decode produces. Its planes
         * live in memory the decoder's pool owns, in a layout the CPU may address only under a
         * lock; the reason to carry it is that a GL context can bind it as a texture without a
         * copy, and the reason it is not planar is that everything above cannot.
         *
         * <p>The handle is valid exactly while the frame is held, and no longer:
         * {@link #release()} hands that buffer back to the decoder's pool, which will refill it.
         */
        IO_SURFACE,
    }

    private static final int MAX_PLANES = 3;

    private static final VarHandle GENERATION;

    static {
        try {
            GENERATION = MethodHandles.lookup()
                    .findVarHandle(VideoFrame.class, "generation", long.class);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private final int slot;
    private final Recycler recycler;
    private final ByteBuffer[] planes = new ByteBuffer[MAX_PLANES];
    private final int[] strides = new int[MAX_PLANES];
    private final int[] extents = new int[MAX_PLANES];

    private int width;
    private int height;
    private PixelFormat format = PixelFormat.I420;
    private VideoColor color = VideoColor.unspecified();
    private long ptsMicros = PTS_UNKNOWN;
    private Kind kind = Kind.PLANAR;
    private long handle;
    private Downloader downloader;

    /**
     * True only inside {@link #toPlanar()}, and it is what lets a producer re-point a frame's
     * planes while a consumer holds it, the one mutation of a published frame that exists. Any
     * other write to a held frame is the bug the publication flag prevents.
     */
    private boolean downloading;

    /**
     * Parity is the liveness flag and the value is the staleness token, in one field so the two
     * cannot disagree: even means the producer owns the slot, odd means a consumer holds it.
     * Splitting them into a boolean and a counter is the wrong edit this shape prevents.
     */
    private volatile long generation;

    private VideoFrame(int slot, Recycler recycler) {
        this.slot = slot;
        this.recycler = recycler;
    }

    /** @return visible picture width in pixels; still readable after {@link #release()} */
    public int width() {
        return width;
    }

    /** @return visible picture height in pixels; still readable after {@link #release()} */
    public int height() {
        return height;
    }

    /** @return the plane layout of this picture, never null; still readable after {@link #release()} */
    public PixelFormat format() {
        return format;
    }

    /** @return how this picture's samples are to be interpreted, never null; readable after release */
    public VideoColor color() {
        return color;
    }

    /**
     * Distance in <em>bytes</em> between the starts of two consecutive rows of {@code plane}, not
     * samples and not pixels. At least the plane's byte width and often more, and an arbitrary byte
     * count rather than a multiple of four, so a consumer that assumes four-byte row alignment
     * skews every frame whose width is not a multiple of four.
     *
     * <p>Still readable after {@link #release()}.
     *
     * @throws IndexOutOfBoundsException if {@code plane} is not a plane of {@link #format()}
     */
    public int stride(int plane) {
        checkPlane(plane);
        return strides[plane];
    }

    /**
     * The bytes of one plane, read-only, positioned at 0 with the limit at the plane's extent. Row
     * <i>r</i> begins at {@code r * stride(plane)} and carries the plane's byte width of picture;
     * the remainder of each row is padding holding anything at all.
     *
     * <p>The memory belongs to the producer. It is valid until {@link #release()} and is then
     * refilled with another picture; copy or upload anything that must outlive the frame. The same
     * buffer instance is returned for the life of the slot, so its position and limit are shared
     * state; they are reset on every publication, and a consumer that reads relatively should
     * duplicate the buffer first rather than leave it consumed for the next one.
     *
     * <p>Direct or heap-backed, whichever the producer bound. Either way the view returned is
     * read-only, so a heap-backed plane is not array-accessible: {@code hasArray()} is false and
     * {@code array()} throws. Read samples with {@code get(index)}, and test {@code isDirect()}
     * before assuming there is an address to hand to a device.
     *
     * <p><b>A handle-backed picture has no planes and this throws for one.</b> It does not return
     * an empty buffer, a stale one, or the planes of whatever the slot held last: a
     * {@link Kind#PLANAR} picture is the only one whose samples are in memory a consumer may read,
     * and a consumer that meets any other kind either binds the {@link #handle()} or asks
     * {@link #toPlanar()} to read it back. Answering plausibly here would put a wrong picture on
     * the screen instead of an exception on the one line that can explain it.
     *
     * @throws IllegalStateException         if the frame has been released, or was never published
     * @throws UnsupportedOperationException if {@link #kind()} is not {@link Kind#PLANAR}
     * @throws IndexOutOfBoundsException     if {@code plane} is not a plane of {@link #format()}
     */
    public ByteBuffer plane(int plane) {
        if ((generation & 1L) == 0L) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + " is not held: its planes belong to the producer");
        }
        if (kind != Kind.PLANAR) {
            throw new UnsupportedOperationException(
                    "VideoFrame slot " + slot + " carries a " + kind + " device handle and no"
                            + " readable samples; bind handle(), or call toPlanar() to have the"
                            + " producer read it back into memory");
        }
        checkPlane(plane);
        return planes[plane];
    }

    /**
     * @return whether this picture is samples in memory or a handle to a device allocation; still
     *         readable after {@link #release()}, and changed by {@link #toPlanar()}
     */
    public Kind kind() {
        return kind;
    }

    /**
     * The device allocation this picture lives in, as the opaque integer {@link #kind()} names.
     *
     * <p>What it is worth to a consumer depends entirely on the kind, and a consumer that does not
     * recognise the kind must refuse rather than guess: the same {@code long} is a
     * reference-counted CoreVideo surface on one platform and a file descriptor on another.
     *
     * <p><b>It is borrowed for exactly as long as the frame is.</b> The producer hands the
     * allocation back to its own pool on {@link #release()} and refills it, so a device object
     * derived from this (a texture bound onto it, say) stops meaning this picture at that moment,
     * and any device work that reads it must have completed first. Nothing here can enforce that:
     * it is the consumer's, and it is the hardest lifetime in the subsystem.
     *
     * @return the handle, never 0 while the frame is held
     * @throws IllegalStateException         if the frame has been released, or was never published
     * @throws UnsupportedOperationException if {@link #kind()} is {@link Kind#PLANAR}
     */
    public long handle() {
        if ((generation & 1L) == 0L) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + " is not held: its memory belongs to the producer");
        }
        if (kind == Kind.PLANAR) {
            throw new UnsupportedOperationException(
                    "VideoFrame slot " + slot + " carries planar samples, not a device handle;"
                            + " read plane(int)");
        }
        return handle;
    }

    /**
     * Makes this picture's samples readable, reading a device allocation back into memory if that
     * is what it is. A {@link Kind#PLANAR} picture is already readable and this does nothing.
     *
     * <p>This is the download that every consumer without a device needs, and it is not free: it
     * moves the whole picture out of the decoder's memory (3.1 MB at 1080p 4:2:0, 12.4 MB at 4K)
     * across whatever bus separates them, per picture. A consumer that <em>can</em> bind
     * {@link #handle()} must do that instead; this exists so that the ones that cannot (a software
     * converter, a writer, a test) are not simply broken by a decoder they did not choose.
     *
     * <p>Afterwards {@link #kind()} is {@link Kind#PLANAR} for the rest of this lease and
     * {@link #handle()} throws. Releasing the frame is unchanged and still required exactly once.
     *
     * <p>Whichever thread holds the frame, and not two at once: this re-points the frame's planes,
     * which is otherwise something only a producer may do to a frame nobody is holding.
     *
     * @throws IllegalStateException         if the frame has been released, or was never published
     * @throws UnsupportedOperationException if the producer offers no download for this kind
     */
    public void toPlanar() {
        if ((generation & 1L) == 0L) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + " is not held, so there is nothing to read back");
        }
        if (kind == Kind.PLANAR) {
            return;
        }
        if (downloader == null) {
            throw new UnsupportedOperationException(
                    "VideoFrame slot " + slot + " carries a " + kind + " handle and its producer"
                            + " offers no way to read one back into memory");
        }
        downloading = true;
        try {
            downloader.download(this);
        } finally {
            downloading = false;
        }
        if (kind != Kind.PLANAR) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + "'s producer did not produce planes for the"
                            + " download it was asked for");
        }
    }

    /**
     * Presentation time in microseconds measured from the start of the stream, not from the epoch,
     * and not from when decoding began. Non-decreasing across the frames one source delivers,
     * because any reordering happens inside the producer; consecutive equal values are legal, so it
     * is not strictly increasing.
     *
     * <p>Microseconds because a 90 kHz container tick is 11.11 of them, finer than the container's
     * own clock, while a {@code long} still spans far more than any stream's length. Milliseconds
     * would quantize a 60-per-second frame interval into visible judder.
     *
     * @return the presentation time, or {@link #PTS_UNKNOWN} when the source has no timing at all
     */
    public long ptsMicros() {
        return ptsMicros;
    }

    /**
     * @return this frame's index in its producer's pool, stable for the life of the pool: the
     *         integer that identifies the memory to the side that owns it, for diagnostics and for
     *         a producer's own bookkeeping
     */
    public int slot() {
        return slot;
    }

    /**
     * A counter that changes on every publication and every release of this slot, odd exactly while
     * a consumer holds the frame. A holder that keeps a frame across several of its own frames
     * reads this at delivery and compares before using it: an unchanged value means the picture is
     * still the one it was handed. This is the only way to detect a stale reference, because a
     * recycled frame is otherwise indistinguishable from a live one.
     *
     * @return the publication counter, never decreasing
     */
    public long generation() {
        return generation;
    }

    /**
     * Hands this frame's buffers back to the producer that published it. The producer owns the
     * memory, always; a consumer borrows a frame between delivery and this call and owns nothing.
     * Exactly one release per delivered frame.
     *
     * <p>A pool has a fixed, small number of slots. A frame that is never released removes one
     * permanently, and the producer stalls as soon as the remaining slots are all in flight. A
     * video that plays for a second and then freezes with no error and no exception is this bug,
     * and it is why this call has no convenient alternative.
     *
     * <p>Afterwards the frame is dead to the caller: drop the reference. {@link #plane(int)} throws
     * until the producer refills the slot, and once it does, this same instance carries a different
     * picture. The descriptive accessors keep answering, so a released frame can still be logged.
     *
     * <p>Any thread: a frame may be handed between threads and released on a different one from the
     * one it was delivered to. Two threads racing to release the same frame cannot both succeed.
     *
     * @throws IllegalStateException if the frame is not currently held. A slot returned twice is
     *                               handed to two producers at once, and the picture corruption
     *                               that follows appears nowhere near this call.
     */
    public void release() {
        long held = generation;
        if ((held & 1L) == 0L || !GENERATION.compareAndSet(this, held, held + 1L)) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + " is not currently held; it was released already");
        }
        recycler.recycle(this);
    }

    /** Never throws, released or not: {@code VideoFrame[slot=2 1920x1080 NV12 IO_SURFACE pts=…]} */
    @Override
    public String toString() {
        String pts = ptsMicros == PTS_UNKNOWN ? "unknown" : ptsMicros + "us";
        return "VideoFrame[slot=" + slot + " " + width + "x" + height + " " + format
                + (kind == Kind.PLANAR ? "" : " " + kind)
                + " pts=" + pts + " gen=" + generation + "]";
    }

    private void checkPlane(int plane) {
        if (plane < 0 || plane >= format.planeCount()) {
            throw new IndexOutOfBoundsException(
                    format + " has planes 0.." + (format.planeCount() - 1) + ", got " + plane);
        }
    }

    private void checkNotPublished() {
        if ((generation & 1L) != 0L) {
            throw new IllegalStateException(
                    "VideoFrame slot " + slot + " is published and held by a consumer");
        }
    }

    /**
     * How a producer gets a released frame's memory back. The pool lives entirely on the producer's
     * side: this carries {@link VideoFrame#slot()} and never an address, so a native producer's
     * implementation is one call passing that integer across the boundary and a pure-Java
     * producer's is that integer pushed onto a free list.
     *
     * <p>Invoked once per released frame, on whichever thread released it, so implementations must
     * be thread-safe. It must not wait for anything: a producer blocked for a free slot stays
     * blocked until this returns.
     */
    @FunctionalInterface
    public interface Recycler {

        /**
         * Takes back the memory of a frame whose consumer has just released it. The slot is already
         * marked free when this runs, so {@link VideoFrame#plane(int)} throws here and the frame is
         * usable only through {@link VideoFrame#slot()} and the descriptive accessors, which is
         * everything a free list or a native handback needs.
         *
         * @param frame the frame whose slot came free; never null
         */
        void recycle(VideoFrame frame);

        /**
         * Recycles nothing, and exists for frames backed by ordinary garbage-collected memory
         * whose producer will never refill the slot. {@link VideoFrame#release()} is still
         * required against this: it is what stops the planes being readable, and a consumer that
         * omits it here will omit it against a real producer too.
         */
        Recycler NONE = frame -> {
        };
    }

    /**
     * How a producer reads one of its own device pictures back into memory a consumer can address,
     * when {@link VideoFrame#toPlanar()} asks it to. A producer that only ever publishes planar
     * samples never installs one.
     *
     * <p>An implementation moves the picture ({@code av_hwframe_transfer_data}, a staging copy, a
     * mapped lock) and then points the frame's planes at the result through the {@link Writer} it
     * kept for this slot, ending with {@link Writer#downloaded()}. It is called with the frame
     * <em>held</em>, which is the one time a producer may re-point a published frame, and the flag
     * that permits it is cleared as soon as this returns.
     *
     * <p>Called on whichever thread holds the frame (not necessarily the decode thread), so an
     * implementation that reaches a decoder must take whatever lock that decoder is guarded by.
     */
    @FunctionalInterface
    public interface Downloader {

        /**
         * Reads {@code frame}'s device picture into memory and rebinds its planes.
         *
         * @param frame the held frame to fill; its {@link VideoFrame#slot()} identifies the picture
         *              to the side that owns it, exactly as {@link Recycler}'s does
         */
        void download(VideoFrame frame);
    }

    /**
     * A producer's handle on one pooled frame: the only thing that can point a frame at memory or
     * publish it. A pool builds one writer per slot at startup and keeps it; a consumer is handed
     * the frame alone and therefore cannot retarget a frame it does not own, which is a property of
     * the type rather than a comment asking nicely.
     *
     * <p>A nested class rather than package-private methods on purpose: producers live in other
     * packages and other modules, and package-private access across a module boundary requires a
     * split package, which fails as soon as anything declares a module descriptor. A nested class
     * reaches the frame's private state from anywhere.
     *
     * <p>One thread per slot: only the thread that owns the pool calls these. The steady-state path
     * is {@link #setPtsMicros(long)} then {@link #publish()}: two primitive writes, nothing
     * created. {@link #configure} and {@link #setPlane} are for pool construction and for a format
     * change; calling them per picture is the mistake that reintroduces per-frame allocation.
     */
    public static final class Writer {

        private final VideoFrame frame;

        private Writer(VideoFrame frame) {
            this.frame = frame;
        }

        /**
         * Builds one pooled frame and the writer that publishes it. Pool construction only.
         *
         * @param slot     this frame's index in the pool, at least 0
         * @param recycler where {@link VideoFrame#release()} returns the memory
         * @throws IllegalArgumentException if {@code slot} is negative
         * @throws NullPointerException     if {@code recycler} is null
         */
        public static Writer allocate(int slot, Recycler recycler) {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must be at least 0, got " + slot);
            }
            Objects.requireNonNull(recycler, "recycler");
            return new Writer(new VideoFrame(slot, recycler));
        }

        /** @return the frame this writer publishes, the same instance for the pool's lifetime */
        public VideoFrame frame() {
            return frame;
        }

        /**
         * Sets geometry and interpretation and invalidates every plane binding, so {@link #setPlane}
         * must be called for all of the format's planes before the next {@link #publish()}.
         *
         * @param width  visible width in pixels, in {@code [1..PixelFormat.MAX_DIMENSION]}
         * @param height visible height in pixels, in the same range
         * @throws IllegalArgumentException if either dimension is outside that range
         * @throws IllegalStateException    if the frame is currently published
         * @throws NullPointerException     if {@code format} or {@code color} is null
         */
        public void configure(int width, int height, PixelFormat format, VideoColor color) {
            frame.checkNotPublished();
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(color, "color");
            if (width < 1 || width > PixelFormat.MAX_DIMENSION) {
                throw new IllegalArgumentException(
                        "width must be in [1.." + PixelFormat.MAX_DIMENSION + "], got " + width);
            }
            if (height < 1 || height > PixelFormat.MAX_DIMENSION) {
                throw new IllegalArgumentException(
                        "height must be in [1.." + PixelFormat.MAX_DIMENSION + "], got " + height);
            }
            frame.width = width;
            frame.height = height;
            frame.format = format;
            frame.color = color;
            frame.kind = Kind.PLANAR;
            frame.handle = 0L;
            for (int plane = 0; plane < MAX_PLANES; plane++) {
                frame.planes[plane] = null;
                frame.strides[plane] = 0;
                frame.extents[plane] = 0;
            }
        }

        /**
         * Points {@code plane} at {@code buffer}, whose rows are {@code strideBytes} apart. The
         * buffer is stored as a read-only view; its capacity must be at least the plane's minimum
         * byte count for the configured size and this stride, and may be more; a frame cropped out
         * of a larger coded picture is expressed exactly this way, by slicing each plane so byte 0
         * is the first visible sample and leaving the stride at the coded width.
         *
         * <p>Direct or heap: the toolkit reads either. A consumer that uploads to a device may
         * require direct, which is that consumer's precondition and not this one's.
         *
         * @param strideBytes bytes between the starts of consecutive rows; rows run top-down, so a
         *                    producer holding bottom-up rows flips on its own side
         * @throws IllegalArgumentException  if the stride is below the plane's byte width, or the
         *                                   buffer is smaller than the plane needs
         * @throws IllegalStateException     if the frame is currently published (<em>except</em>
         *                                   inside a {@link Downloader}, which is the one moment a
         *                                   producer may re-point a held frame), or if no size has
         *                                   been configured yet
         * @throws IndexOutOfBoundsException if {@code plane} is not a plane of the configured format
         * @throws NullPointerException      if {@code buffer} is null
         */
        public void setPlane(int plane, ByteBuffer buffer, int strideBytes) {
            if (!frame.downloading) {
                frame.checkNotPublished();
                // Binding samples is what makes a picture planar, and a slot that carried a device
                // handle last time must not keep it: a stream that falls back from hardware to
                // software mid-flight would otherwise publish real planes under a stale handle, and
                // the consumer that binds handles would bind an allocation the decoder has taken
                // back. Inside a download the flip belongs to downloaded(), which checks first.
                frame.kind = Kind.PLANAR;
                frame.handle = 0L;
            }
            frame.checkPlane(plane);
            Objects.requireNonNull(buffer, "buffer");
            if (frame.width < 1 || frame.height < 1) {
                throw new IllegalStateException(
                        "VideoFrame slot " + frame.slot + " has no size yet; configure it first");
            }
            long needed = frame.format.minPlaneBytes(plane, frame.width, frame.height, strideBytes);
            if (buffer.capacity() < needed) {
                throw new IllegalArgumentException(
                        "plane " + plane + " needs " + needed + " bytes at stride " + strideBytes
                                + ", buffer holds " + buffer.capacity());
            }
            frame.planes[plane] = buffer.asReadOnlyBuffer();
            frame.strides[plane] = strideBytes;
            frame.extents[plane] = (int) needed;
        }

        /**
         * Sets the presentation time in microseconds from the start of the stream, or
         * {@link VideoFrame#PTS_UNKNOWN} when the producer has no timing at all. Survives
         * {@link VideoFrame#release()}, so a producer that does not set it per picture republishes
         * the previous one.
         */
        public void setPtsMicros(long ptsMicros) {
            frame.ptsMicros = ptsMicros;
        }

        /**
         * Makes the next {@link #publish()} a device picture rather than a planar one: this frame
         * will carry {@code handle}, {@link VideoFrame#plane(int)} will refuse, and every plane
         * binding is dropped.
         *
         * <p>The geometry and the {@link PixelFormat} still describe the picture (a VideoToolbox
         * NV12 surface is NV12, and the layout is what a consumer needs to bind or convert it), but
         * the samples are not in memory this side can address.
         *
         * <p>Set per picture, unlike {@link #setPlane}: a decoder's pool hands out a different
         * allocation each time, and a handle is one field rather than a read-only view, so writing
         * it per picture allocates nothing.
         *
         * @param kind   which family of handle this is; not {@link Kind#PLANAR}
         * @param handle the device allocation, not 0
         * @throws IllegalArgumentException if {@code kind} is {@link Kind#PLANAR} or {@code handle}
         *                                  is 0
         * @throws IllegalStateException    if the frame is currently published
         * @throws NullPointerException     if {@code kind} is null
         */
        public void setHandle(Kind kind, long handle) {
            frame.checkNotPublished();
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.PLANAR) {
                throw new IllegalArgumentException(
                        "PLANAR is the absence of a handle; bind planes with setPlane instead");
            }
            if (handle == 0L) {
                throw new IllegalArgumentException("a device handle of 0 is no handle at all");
            }
            frame.kind = kind;
            frame.handle = handle;
            for (int plane = 0; plane < MAX_PLANES; plane++) {
                frame.planes[plane] = null;
                frame.strides[plane] = 0;
                frame.extents[plane] = 0;
            }
        }

        /**
         * Installs the producer's way of reading one of its device pictures back into memory, for
         * the consumers that cannot use a handle. Pool construction; a producer that publishes only
         * planar pictures never calls it, and {@link VideoFrame#toPlanar()} then refuses rather
         * than pretending.
         *
         * @param downloader what {@link VideoFrame#toPlanar()} calls; null removes it
         */
        public void setDownloader(Downloader downloader) {
            frame.downloader = downloader;
        }

        /**
         * Ends a {@link Downloader}'s work: the planes it has just bound become the picture, and
         * this frame stops being handle-backed for the rest of the lease the consumer is holding.
         *
         * @throws IllegalStateException if called outside a download, or a plane is still unbound
         */
        public void downloaded() {
            if (!frame.downloading) {
                throw new IllegalStateException(
                        "VideoFrame slot " + frame.slot + " is not being downloaded; only a"
                                + " Downloader may turn a device picture into a planar one");
            }
            int planeCount = frame.format.planeCount();
            for (int plane = 0; plane < planeCount; plane++) {
                if (frame.planes[plane] == null) {
                    throw new IllegalStateException(
                            "VideoFrame slot " + frame.slot + " plane " + plane
                                    + " is unbound for " + frame.format);
                }
            }
            rewindPlanes(planeCount);
            frame.kind = Kind.PLANAR;
            frame.handle = 0L;
        }

        /**
         * Rewinds every plane view, marks the frame held and returns it for handoff. The generation
         * counter is written last and is written volatile, so a consumer that reads
         * {@link VideoFrame#generation()} before anything else sees a fully initialized frame even
         * when the handoff itself carries no ordering of its own.
         *
         * @return the frame, now held by whoever receives it
         * @throws IllegalStateException if the frame is already published, or carries neither every
         *                               plane of its format nor a device handle
         */
        public VideoFrame publish() {
            frame.checkNotPublished();
            int planeCount = frame.format.planeCount();
            if (frame.kind == Kind.PLANAR) {
                for (int plane = 0; plane < planeCount; plane++) {
                    if (frame.planes[plane] == null) {
                        throw new IllegalStateException(
                                "VideoFrame slot " + frame.slot + " plane " + plane
                                        + " is unbound for " + frame.format);
                    }
                }
                rewindPlanes(planeCount);
            } else if (frame.handle == 0L) {
                throw new IllegalStateException(
                        "VideoFrame slot " + frame.slot + " is " + frame.kind
                                + " and carries no handle");
            }
            frame.generation = frame.generation + 1L;
            return frame;
        }

        private void rewindPlanes(int planeCount) {
            for (int plane = 0; plane < planeCount; plane++) {
                ByteBuffer view = frame.planes[plane];
                view.clear();
                view.limit(frame.extents[plane]);
            }
        }
    }
}
