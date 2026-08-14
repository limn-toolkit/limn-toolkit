package limn.video;

/**
 * A pull source of decoded pictures: the video counterpart of an incrementally decoded audio
 * stream, obtained from a {@link VideoDecoder} and driven one picture at a time.
 *
 * <p>A consumer cannot supply the destination: one 1080p picture in 4:2:0 is about 3.1 MB, and
 * handing out a fresh one per picture is roughly 93 MB a second of garbage at 30 per second.
 * Pictures are therefore owned by the source and pooled; {@link #readFrame()} says what happened
 * and {@link #frame()} lends the result, which the consumer returns with
 * {@link VideoFrame#release()}. Nothing on this path allocates.
 *
 * <p>Every metadata accessor ({@link #width()}, {@link #height()}, {@link #pixelFormat()},
 * {@link #color()}, {@link #rotationDegrees()}, {@link #frameRateNum()}, {@link #frameRateDen()},
 * {@link #durationMicros()}, {@link #canReset()} and {@link #canSeek()}) is fixed when the source
 * is opened, never changes, and is readable from any thread. A source that cannot answer them
 * before its first picture is decoded is not a valid implementation: a view has to be laid out
 * before a picture exists.
 *
 * <p>Threading: after a player takes ownership, {@link #readFrame()}, {@link #reset()},
 * {@link #seek(long, SeekMode)} and the final {@link #close()} come from that player's single
 * decode thread, never concurrently, serialized by the player. Implementations need no
 * synchronization but must not assume any particular thread. The metadata accessors are the
 * exception: they are read concurrently and must therefore be final state rather than lazily
 * computed. {@link #readFrame()} may block for a whole decode and must never be called on a thread
 * that is drawing, and {@link #seek(long, SeekMode)} may block for as long as a read does.
 */
public interface VideoStreamSource extends AutoCloseable {

    /** What {@link #durationMicros()} reports when the length is not knowable. */
    long DURATION_UNKNOWN = -1;

    /**
     * How close to the target a {@link #seek(long, SeekMode) seek} is asked to land. The two are
     * different costs rather than degrees of the same one: a container can jump to an independently
     * decodable picture for the price of an index lookup, and reaching anything between two of them
     * means decoding every picture in between and throwing it away.
     *
     * <p>Both are wanted by the same transport control seconds apart: {@link #KEYFRAME} while a
     * thumb is being dragged, so that dragging costs one cheap seek per update rather than a decode
     * per pixel, and {@link #EXACT} when it is let go, because that is where the viewer says the
     * video should be.
     */
    enum SeekMode {

        /**
         * The next picture's presentation time is at or before the target, as close to it as the
         * source can get without decoding pictures it would discard. The distance is whatever the
         * input's structure imposes and is not knowable in advance: seconds, for a container whose
         * independently decodable pictures are seconds apart.
         *
         * <p>A source whose every picture is independently decodable (a raw format, a generator)
         * has nothing to skip past, so this lands on the picture immediately at or before the
         * target: the one that would be on screen at that instant, for no cost at all.
         */
        KEYFRAME,

        /**
         * The next picture is the first whose presentation time is at or after the target: under one
         * picture interval late rather than any amount early. Costs whatever decoding from the last
         * independently decodable picture costs, all of it discarded.
         */
        EXACT
    }

    /** The outcome of a {@link #readFrame()} call. */
    enum Read {

        /** A new picture is ready in {@link #frame()} and is held by the caller until released. */
        FRAME,

        /**
         * No picture right now, but more are coming; retry later, and never treat this as the end.
         * Two things produce it: a source that has not finished producing one, and a source all of
         * whose pooled slots are currently held by the consumer. Both are answered the same way, by
         * releasing what is held and asking again. It must be cheap and must not spin.
         */
        PENDING,

        /** No more pictures will ever arrive. Calling again keeps returning this. */
        END
    }

    /** @return width in pixels of the luma plane */
    int width();

    /** @return height in pixels of the luma plane */
    int height();

    /** @return the plane layout every picture from this source uses */
    PixelFormat pixelFormat();

    /** @return how every picture from this source is to be interpreted; never null */
    VideoColor color();

    /**
     * How far the picture must be turned <em>clockwise</em> to be displayed the right way up. Every
     * recording made on a device that can be held sideways carries this, stored one way and meant to
     * be seen another, and a consumer that ignores it shows a portrait recording on its side.
     *
     * <p>The samples are not turned: {@link #width()} and {@link #height()} describe the picture as
     * it is stored and every plane's geometry follows them, so at 90 or 270 the <em>displayed</em>
     * width is {@code height()} and the displayed height is {@code width()}. A consumer that lays
     * out a box for this stream swaps them; one that only uploads planes ignores this entirely.
     *
     * @return 0, 90, 180 or 270, and nothing else. An implementation whose input describes a flip,
     *         a shear or an angle off the quarter turns reports 0 rather than the nearest right
     *         angle, because a picture silently shown mirrored is worse than one shown as stored.
     */
    default int rotationDegrees() {
        return 0;
    }

    /**
     * Numerator of the nominal frame rate, kept as a rational so that rates like 30000/1001 are
     * exact; a rate held as a fraction of a second drifts by a whole picture every few minutes.
     * Nominal means what to expect, not what will arrive: a source whose pictures are unevenly
     * spaced still reports its nominal rate here and puts the truth in each picture's timestamp.
     *
     * @return the numerator, or 0 when the rate is unknown
     */
    int frameRateNum();

    /** @return denominator of the nominal frame rate; never 0, so a caller may always divide */
    int frameRateDen();

    /**
     * @return total length in microseconds, or {@link #DURATION_UNKNOWN} when the source cannot
     *         know it: a pipe, a live input, a container without a duration. Never an estimate
     *         presented as a fact.
     */
    default long durationMicros() {
        return DURATION_UNKNOWN;
    }

    /**
     * Decodes the next picture.
     *
     * @return what happened; on {@link Read#FRAME} the picture is in {@link #frame()}
     * @throws RuntimeException if the stream is malformed or the decode fails. A failed read is
     *                          exceptional rather than a status, so the steady-state path carries
     *                          no error object; a source that can skip a damaged picture skips it
     *                          and returns the next one instead of throwing.
     */
    Read readFrame();

    /**
     * The picture most recently produced by {@link #readFrame()}, on loan. The source may not refill
     * that slot until the consumer hands it back with {@link VideoFrame#release()}, so exactly one
     * release per delivered picture is still the rule here.
     *
     * <p>After {@link Read#END} this keeps returning that same picture, because no further ones are
     * produced. A player that wants the final image left on screen therefore simply does not release
     * it until it is finished with it, which costs nothing: no slot the source still needs is being
     * withheld.
     *
     * @return the borrowed picture, or null before the first {@link Read#FRAME} and after
     *         {@link #close()}
     */
    VideoFrame frame();

    /**
     * Rewinds to the first picture: how a player loops seamlessly, and the only repositioning this
     * interface defines. Not a seek: no time, no accuracy mode, no failure short of the input being
     * unable to rewind at all.
     *
     * @throws UnsupportedOperationException if {@link #canReset()} is false
     */
    void reset();

    /**
     * @return whether {@link #reset()} works. Asked before playback starts, so a player decides up
     *         front whether to offer looping rather than discovering it by catching an exception at
     *         the end of the input, which reaches the viewer as a stall.
     */
    default boolean canReset() {
        return true;
    }

    /**
     * Moves to {@code micros} so that the next {@link #readFrame()} produces a picture there. Unlike
     * {@link #reset()} this is a position on the same timeline the pictures' presentation times are
     * on, so seeking to a picture's own {@code ptsMicros()} in {@link SeekMode#EXACT} produces that
     * picture.
     *
     * <p><b>What the next picture's timestamp is.</b> In {@link SeekMode#EXACT} it is the first at
     * or after {@code micros}, under one picture interval late, never early. In
     * {@link SeekMode#KEYFRAME} it is at or before {@code micros}, by however much the input's own
     * structure imposes, which for a source whose every picture is independently decodable is
     * nothing at all. Neither mode promises a picture <em>exactly</em> at {@code micros}: a picture
     * exists at the instants the producer put one, and asking for a time between two of them cannot
     * conjure a third.
     *
     * <p><b>Past the end is a position, not an error.</b> A target beyond the last picture leaves
     * the source at the end, and the next read reports {@link Read#END}. A target at or below the
     * first picture's time leaves the source at the beginning. Negative targets are the caller's
     * mistake and are refused.
     *
     * <p><b>What it costs.</b> {@link SeekMode#KEYFRAME} decodes nothing. {@link SeekMode#EXACT}
     * decodes and discards every picture between the nearest independently decodable one and the
     * target, so it is bounded by the input's structure rather than by the distance travelled; a
     * seek of one second can cost more than a seek of one minute.
     *
     * <p><b>Pictures already lent out survive.</b> This does not invalidate a picture the consumer
     * is holding, and every one of them must still be {@linkplain VideoFrame#release() released}
     * exactly once. A consumer that holds every pooled slot across a seek gets {@link Read#PENDING}
     * afterwards, exactly as it would without one.
     *
     * <p>Called on the same thread as {@link #readFrame()} and never concurrently with it.
     *
     * @param micros where to move to, in microseconds on the pictures' own timeline; not negative
     * @param mode   how close to land, and therefore what this costs
     * @throws UnsupportedOperationException if {@link #canSeek()} is false
     * @throws IllegalArgumentException      if {@code micros} is negative
     * @throws RuntimeException              if the input could not be repositioned, which is a
     *                                       failure of the input rather than a property of the
     *                                       source and is therefore thrown rather than reported by
     *                                       {@link #canSeek()}
     */
    default void seek(long micros, SeekMode mode) {
        throw new UnsupportedOperationException(
                getClass().getName() + " cannot seek; ask canSeek() first");
    }

    /**
     * @return whether {@link #seek(long, SeekMode)} works, which defaults to false so that a source
     *         written before seeking existed keeps telling the truth. Asked before a transport
     *         control is offered, so that a scrub bar a stream cannot honour is disabled rather than
     *         throwing under the viewer's finger. Independent of {@link #canReset()}: an input that
     *         can be rewound to its start need not be able to reach the middle.
     */
    default boolean canSeek() {
        return false;
    }

    /**
     * Releases decoder and input resources and invalidates every pooled picture. Idempotent.
     * Afterwards {@link #frame()} is null and {@link #readFrame()} reports the end, so a cleanup
     * block needs no ordering care. A picture a consumer kept across this call refers to storage
     * that no longer exists; copy before closing, never after.
     */
    @Override
    void close();
}
