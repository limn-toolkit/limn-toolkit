package limn.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import limn.concurrent.Work;

/**
 * Image loading and saving facade. Loading is backed by the running backend's single
 * {@link ImageDecoder} (installed at startup, mirroring {@link TextRulers}/{@code Ui}); saving is
 * backed by an ordered list of {@link ImageEncoder}s, because what an encoder can write is chosen
 * by the caller rather than by the input, so more than one can be useful at once.
 *
 * <p>Both directions are pure CPU (the GPU texture is created lazily at draw time, and encoding
 * touches no GPU at all), so both are safe to call from the UI thread during setup, and safe to
 * call from a worker thread. Encoding in particular needs no backend, no window and no GL context:
 * {@link ImageFormat#PNG} works in a headless test.
 *
 * <p>Pure CPU is not the same as quick: every entry point here reads, decodes or compresses on the
 * thread that calls it, which is a stall if that thread is the UI thread and a frame is due.
 * {@link #decodeAsync}, {@link #encodeAsync}, {@link #saveAsync}, {@link #loadShared} and
 * {@link #fromResourceShared} do the same work on the {@code Ui} worker pool and hand the result
 * back on the UI thread; unlike their synchronous counterparts, those need a running backend. The
 * one blocking call with no asynchronous form is
 * {@link #encode(Image, ImageEncodeOptions, OutputStream)}, which says on itself why it cannot
 * have one.
 *
 * <p><b>Two suffixes, and they are not interchangeable.</b> A name ending in {@code Async} returns
 * an unstarted {@link Work}: nothing happens until {@code start()}, and the job can be cancelled.
 * A name ending in {@code Shared} returns a {@code CompletableFuture} that is already running and
 * is de-duplicated by source, so two callers asking for one file get one picture and one texture,
 * and neither of them may cancel what the other is also waiting for.
 *
 * <p>To get an image <em>out</em> of the GPU in the first place, see {@link ReadableSurface} (an
 * offscreen surface) and {@code GpuRenderer.captureFramebuffer} (a window).
 */
public final class Images {

    private static volatile ImageDecoder decoder;

    private static final java.util.concurrent.CopyOnWriteArrayList<ImageEncoder> encoders =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        // The built-in PNG encoder is installed here rather than by a backend, so that encoding
        // works in a process that never starts one.
        encoders.add(PngEncoder.INSTANCE);
    }

    private Images() {
    }

    /** Installs the backend decoder (called once at backend startup). */
    public static void installDecoder(ImageDecoder newDecoder) {
        decoder = Objects.requireNonNull(newDecoder, "newDecoder");
    }

    /** Uninstalls {@code candidate} if it is the installed decoder (backend shutdown). */
    public static void uninstallDecoder(ImageDecoder candidate) {
        if (decoder == candidate) {
            decoder = null;
        }
    }

    /** @return whether a decoder is installed (i.e. a backend is running) */
    public static boolean isDecoderInstalled() {
        return decoder != null;
    }

    /**
     * Decodes an encoded image (PNG/JPG/…) from memory, on the calling thread. Cheap for an icon
     * and not for a photograph, whose decode scales with its pixel count and is a dropped frame
     * when the caller is the UI thread. Use {@link #decodeAsync} outside setup code.
     */
    public static Image decode(byte[] fileBytes) {
        return require().decode(fileBytes);
    }

    /**
     * Reads and decodes an image file on the calling thread: a blocking read followed by a decode,
     * so its cost is the disk's as well as the decoder's and is unbounded on a network volume. Use
     * {@link #loadShared} outside setup code.
     */
    public static Image load(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new UncheckedIOException("reading image " + file, error);
        }
    }

    /**
     * Reads and decodes a classpath resource on the calling thread; see {@link #fromResourceShared}
     * for the form that does it on the worker pool.
     */
    public static Image fromResource(String resource) {
        try (InputStream in = Images.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("image resource missing: " + resource);
            }
            return decode(in.readAllBytes());
        } catch (IOException error) {
            throw new UncheckedIOException("reading image resource " + resource, error);
        }
    }

    // -------------------------------------------------------------- encoding

    /**
     * Installs {@code encoder} at the <b>end</b> of the probe order. Encoders are asked in the
     * order they were installed, and {@link PngEncoder} is already installed when this class
     * loads, so an application that means to replace PNG rather than add a format must
     * {@link #uninstallEncoder} {@link PngEncoder#INSTANCE} first, or its own encoder is never
     * reached for {@link ImageFormat#PNG}. Installing an already installed encoder is a no-op that
     * leaves the order untouched, so running backend startup twice cannot reshuffle priorities.
     *
     * @throws NullPointerException if {@code encoder} is null
     */
    public static void installEncoder(ImageEncoder encoder) {
        Objects.requireNonNull(encoder, "encoder");
        encoders.addIfAbsent(encoder);
    }

    /**
     * Removes {@code encoder}. No-op when it was never installed, and a no-op for null rather than
     * a failure: the asymmetry with {@link #installEncoder} is deliberate, so a cleanup block can
     * uninstall whatever it may or may not have installed without a null check of its own.
     */
    public static void uninstallEncoder(ImageEncoder encoder) {
        encoders.remove(encoder);
    }

    /**
     * Removes every encoder, the built-in PNG one included. After this, encoding anything fails
     * until something is installed. For a test that wants to observe the empty case; restore the
     * default with {@code installEncoder(PngEncoder.INSTANCE)}.
     */
    public static void uninstallAllEncoders() {
        encoders.clear();
    }

    /** @return an immutable snapshot of the installed encoders, in the order they are asked */
    public static java.util.List<ImageEncoder> installedEncoders() {
        return java.util.List.copyOf(encoders);
    }

    /**
     * @return whether some installed encoder claims {@code options}, the non-throwing form of
     *         {@link #encode}, for disabling a menu entry or choosing another format. A true here
     *         does not promise that the encode succeeds.
     * @throws NullPointerException if {@code options} is null
     */
    public static boolean canEncode(ImageEncodeOptions options) {
        Objects.requireNonNull(options, "options");
        for (ImageEncoder encoder : encoders) {
            if (encoder.supports(options)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether {@code format} can be written at {@link ImageEncodeOptions#DEFAULT_QUALITY}. */
    public static boolean canEncode(ImageFormat format) {
        return canEncode(new ImageEncodeOptions(format));
    }

    /**
     * Encodes {@code image} into {@code out} with the first installed encoder that claims
     * {@code options}. The stream is neither flushed nor closed: the caller owns it.
     *
     * <p>If that encoder then fails, the failure propagates and no later encoder is tried: the one
     * that accepted the request is the one that knows what is wrong with it, and replacing that
     * with a generic message would be the worst diagnostic available.
     *
     * <p>The whole compress-and-write runs on the calling thread, and there is deliberately no
     * asynchronous form of this overload: {@code out} belongs to the caller, and only the caller
     * knows whether writing to it from a worker thread is safe. Wrap this call in {@code Ui.work}
     * with a stream you are willing to hand over, or use {@link #encodeAsync} (bytes) or
     * {@link #saveAsync} (a file), both of which own their sink.
     *
     * @throws java.io.IOException            if {@code out} fails
     * @throws UnsupportedOperationException  if no installed encoder claims {@code options}; the
     *                                        message names every encoder asked, in order
     * @throws NullPointerException           if any argument is null
     */
    public static void encode(Image image, ImageEncodeOptions options, OutputStream out)
            throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(out, "out");
        requireEncoder(options).encode(image, options, out);
    }

    /**
     * Encodes {@code image} and returns the file bytes. Convenient, and the whole file is in
     * memory; prefer {@link #encode(Image, ImageEncodeOptions, OutputStream)} or
     * {@link #save(Image, ImageEncodeOptions, Path)} for anything large.
     *
     * <p>Runs the whole compression on the calling thread. At framebuffer sizes that is long enough
     * to be a visible hitch inside a frame, so on the UI thread use {@link #encodeAsync}.
     *
     * @throws UnsupportedOperationException if no installed encoder claims {@code options}
     * @throws UncheckedIOException          never in practice: the sink is a byte array
     */
    public static byte[] encode(Image image, ImageEncodeOptions options) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try {
            encode(image, options, buffer);
        } catch (IOException error) {
            throw new UncheckedIOException("encoding " + options.format(), error);
        }
        return buffer.toByteArray();
    }

    /** Encodes {@code image} as {@code format} at {@link ImageEncodeOptions#DEFAULT_QUALITY}. */
    public static byte[] encode(Image image, ImageFormat format) {
        return encode(image, new ImageEncodeOptions(format));
    }

    /**
     * Encodes {@code image} and writes it to {@code file}, replacing whatever was there and
     * creating the parent directories if they are missing. Encode and write both happen on the
     * calling thread; see {@link #saveAsync} for the form that moves both to the worker pool.
     *
     * @throws UnsupportedOperationException if no installed encoder claims {@code options}
     * @throws UncheckedIOException          if the file cannot be written
     */
    public static void save(Image image, ImageEncodeOptions options, Path file) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(file, "file");
        ImageEncoder encoder = requireEncoder(options);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file))) {
                encoder.encode(image, options, out);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("writing image " + file, error);
        }
    }

    /**
     * Saves {@code image} as {@code format} at {@link ImageEncodeOptions#DEFAULT_QUALITY}.
     *
     * <p>The format is a parameter and is never inferred from the file name. A suffix is a hint
     * that anyone can get wrong, and inferring from it turns a mistyped name into a file whose
     * contents disagree with its extension, which nothing downstream can detect.
     */
    public static void save(Image image, ImageFormat format, Path file) {
        save(image, new ImageEncodeOptions(format), file);
    }

    /**
     * Encodes and writes {@code file} on the {@code Ui} worker pool and hands {@code file} back to
     * {@code onSuccess} on the UI thread. Requires a running backend, unlike the synchronous form.
     *
     * <p>Returned <b>unstarted</b>, like everything else here whose name ends in {@code Async}:
     * attach the handlers, then {@code start()}. Dropping it writes nothing at all.
     *
     * <p>This is where the work belongs when the image came from a
     * {@link ReadableSurface#readDisplayReferred() readback}: the read itself is a GPU operation
     * and cannot leave the UI thread, but the encode that follows it is plain CPU work on a
     * finished {@link Image}, and at framebuffer sizes it is long enough to be a visible hitch if
     * it runs inside a frame.
     *
     * <p>The result is a path with nothing to release, so no {@code onDiscarded} is attached; a
     * cancelled save may still have written the file, because cancelling stops the delivery and
     * not the body.
     *
     * @throws NullPointerException  if any argument is null
     * @throws IllegalStateException if no backend is running
     */
    public static Work<Path> saveAsync(Image image, ImageEncodeOptions options, Path file) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(file, "file");
        return limn.concurrent.Ui.work(progress -> {
            save(image, options, file);
            return file;
        });
    }

    /**
     * Compresses {@code image} on the {@code Ui} worker pool and hands the file bytes to
     * {@code onSuccess} on the UI thread, the in-memory counterpart of {@link #saveAsync}, for the
     * caller who wants the bytes rather than a file: an upload, a clipboard payload, a diff against
     * a reference. Requires a running backend, unlike the synchronous {@link #encode}.
     *
     * <p>Returned <b>unstarted</b>, so the caller can attach handlers first:
     *
     * <pre>{@code
     * Images.encodeAsync(shot, new ImageEncodeOptions(ImageFormat.PNG))
     *       .onSuccess(bytes -> clipboard.set(bytes))
     *       .onFailure(error -> status.setText(error.getMessage()))
     *       .deliverIf(this::isAttached)
     *       .start();
     * }</pre>
     *
     * <p>Every failure arrives at {@code onFailure} on the UI thread, including "no installed
     * encoder claims these options": the encoder is chosen when the body runs, not when this
     * returns, so a format nothing can write is a delivered failure rather than a throw at the call
     * site. The result is a plain array with nothing to release, so no {@code onDiscarded} is
     * attached and a result nobody takes is simply garbage.
     *
     * <p>No progress is reported: an encoder is one call with no interior to report from. And
     * cancelling after the body has begun does not stop the compression (it only prevents the
     * delivery) because the pool is never interrupted.
     *
     * @param image   the picture to compress; read but not retained
     * @param options format and quality, as for {@link #encode(Image, ImageEncodeOptions)}
     * @throws NullPointerException  if any argument is null
     * @throws IllegalStateException if no backend is running
     */
    public static Work<byte[]> encodeAsync(Image image, ImageEncodeOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        return limn.concurrent.Ui.work(progress -> encode(image, options));
    }

    private static ImageEncoder requireEncoder(ImageEncodeOptions options) {
        Objects.requireNonNull(options, "options");
        StringBuilder asked = new StringBuilder();
        for (ImageEncoder encoder : encoders) {
            if (encoder.supports(options)) {
                return encoder;
            }
            if (asked.length() > 0) {
                asked.append(", ");
            }
            asked.append(encoder.name());
        }
        throw new UnsupportedOperationException("No ImageEncoder accepts " + options.format()
                + " (tried, in order: " + (asked.length() == 0 ? "none installed" : asked) + ")");
    }

    // -------------------------------------------------- background loading

    private static final java.util.concurrent.ConcurrentHashMap<
            String, java.util.concurrent.CompletableFuture<Image>> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Reads and decodes {@code file} on the {@code Ui} worker pool; the returned future is
     * <b>already running</b> and completes on the UI thread, so
     * {@code loadShared(file).thenAccept(view::setImage)} sets the image where widget mutation is
     * legal. Requires a running backend (a {@code Ui} runtime and an installed decoder), unlike
     * the synchronous {@link #load}.
     *
     * <p><b>Shared, which is why this is not a {@code Work} and not called {@code loadAsync}.</b>
     * Loads are de-duplicated by absolute path: concurrent and later calls for the same path
     * return the <em>same</em> future and therefore the same {@link Image} instance, which is what
     * makes one texture serve every user of that file; the backend's texture cache keys by
     * instance identity, so decoding the same file twice would upload it twice. A result with two
     * consumers cannot also be a job either of them may cancel, so there is nothing to start and
     * nothing to withdraw here. A failed load is evicted, so a later call retries rather than
     * replaying the failure forever, and {@link #clearSharedCache()} forces a re-read after the
     * file changed on disk.
     *
     * <p>The failure reaches observers through the future, on the UI thread
     * ({@code exceptionally}, {@code whenComplete}); nothing is thrown from this method beyond an
     * argument check.
     */
    public static java.util.concurrent.CompletableFuture<Image> loadShared(Path file) {
        return cachedLoad("file:" + file.toAbsolutePath(), () -> load(file));
    }

    /**
     * Reads and decodes a classpath resource on the {@code Ui} worker pool; the returned future is
     * already running and completes on the UI thread. De-duplicated by resource name, retried
     * after a failure and failing through the future exactly as {@link #loadShared} describes, and
     * likewise requiring a running backend.
     */
    public static java.util.concurrent.CompletableFuture<Image> fromResourceShared(String resource) {
        return cachedLoad("resource:" + resource, () -> fromResource(resource));
    }

    /**
     * Decodes in-memory bytes on the {@code Ui} worker pool and hands the picture to
     * {@code onSuccess} on the UI thread, carrying the decoder's failure to {@code onFailure} when
     * it throws. Requires a running backend.
     *
     * <p>Returned <b>unstarted</b>: attach the handlers, then {@code start()}.
     *
     * <p>Uncached, unlike {@link #loadShared}: bytes have no stable name to de-duplicate by, so
     * two calls with the same array decode twice and produce two {@link Image} instances, and so
     * two GPU textures. Hold the result rather than re-decoding. Being unshared is exactly what
     * lets this one be a cancellable job.
     *
     * <p>The caller keeps ownership of {@code fileBytes} and must not modify the array until the
     * job has delivered: the decode reads it from a worker thread.
     *
     * @throws IllegalStateException if no backend is running
     */
    public static Work<Image> decodeAsync(byte[] fileBytes) {
        return limn.concurrent.Ui.work(progress -> decode(fileBytes));
    }

    /**
     * Drops every shared load, so the next {@link #loadShared} or {@link #fromResourceShared} of a
     * source reads it again (e.g. after files changed on disk). {@link Image} instances already
     * handed out are unaffected and keep their textures; only the mapping from source to future is
     * cleared. Any thread.
     */
    public static void clearSharedCache() {
        pending.clear();
    }

    private static java.util.concurrent.CompletableFuture<Image> cachedLoad(
            String key, java.util.function.Supplier<Image> loader) {
        return pending.computeIfAbsent(key, k -> {
            java.util.concurrent.CompletableFuture<Image> future =
                    limn.concurrent.Ui.async(loader).toCompletableFuture();
            future.whenComplete((image, error) -> {
                if (error != null) {
                    pending.remove(k, future); // failures are retryable
                }
            });
            return future;
        });
    }

    private static ImageDecoder require() {
        ImageDecoder current = decoder;
        if (current == null) {
            throw new IllegalStateException(
                    "No ImageDecoder installed: start a Backend before loading images.");
        }
        return current;
    }
}
