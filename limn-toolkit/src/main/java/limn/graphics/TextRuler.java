package limn.graphics;

/**
 * Measures text without a frame in flight: what widget layout uses (the
 * layout pass runs before painting, when no {@link Canvas} exists). Metrics
 * are logical points, identical to {@link Canvas#measureText}. Backends
 * install their ruler in {@link TextRulers} at startup; tests inject
 * deterministic fakes.
 */
@FunctionalInterface
public interface TextRuler {

    /** A ruler that measures everything as zero (detached widgets, tests). */
    TextRuler NONE = (text, font) -> new TextMetrics(0, 0, 0, 0);

    /**
     * Measures {@code text} in {@code font}, in logical points, <b>on the UI thread</b>. An
     * implementation may assume that confinement and keep unsynchronized caches; layout, which is
     * the main caller, runs there anyway.
     *
     * <p>There is deliberately no asynchronous form, and one consequence has to be stated rather
     * than discovered. An implementation is allowed to resolve a face on first use, so the first
     * measurement in a family it has not yet loaded may read and parse a font file (tens of
     * megabytes for a CJK face) on this thread, inside whatever frame asked. Since measurement
     * cannot leave the UI thread, that cost cannot be moved off it either; the only lever a caller
     * has is <em>when</em> a new family is first measured, which is why
     * {@link Fonts#setDefaultFamily} belongs on a settings action and not in an animation.
     *
     * @param text the string to measure; empty is legal and measures as zero width
     * @param font the face and size to measure in
     */
    TextMetrics measure(String text, Font font);
}
