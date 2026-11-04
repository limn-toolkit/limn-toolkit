package limn.graphics;

/**
 * Where a face puts the lines and the raised and lowered text it was designed with, in logical
 * points at one size: what an underline, a strike-through, a superscript and a subscript are
 * drawn from, so each sits where the face's designer put it rather than at a fraction of the size.
 *
 * <p>Offsets are measured from the baseline: {@code underlineOffset} and {@code subscriptOffset}
 * downwards, {@code strikeoutOffset} and {@code superscriptOffset} upwards, so each is positive
 * for an ordinary face. A line's offset is to its <em>top</em> edge, as the face's tables write
 * it: the underline fills {@code underlineOffset} to {@code underlineOffset + underlineThickness}
 * below the baseline, the strike-through {@code strikeoutOffset} down to
 * {@code strikeoutOffset - strikeoutThickness} above it. A ruler reads them from the face's own tables ({@code post} and
 * {@code OS/2}); {@link #fallback} is what a ruler with no face to read answers.
 *
 * @param underlineOffset    how far below the baseline the underline's top edge lies
 * @param underlineThickness the underline's thickness, at least a hairline
 * @param strikeoutOffset    how far above the baseline the strike-through's top edge lies
 * @param strikeoutThickness the strike-through's thickness
 * @param superscriptOffset  how far a superscript's baseline is raised
 * @param superscriptSize    the size a superscript is set at
 * @param subscriptOffset    how far a subscript's baseline is lowered
 * @param subscriptSize      the size a subscript is set at
 */
public record TextDecorations(float underlineOffset, float underlineThickness,
                              float strikeoutOffset, float strikeoutThickness,
                              float superscriptOffset, float superscriptSize,
                              float subscriptOffset, float subscriptSize) {

    /**
     * The decorations of a face nobody can read, as fractions of {@code size} close to what most
     * Latin faces declare: an underline a tenth of the em below the baseline, a strike-through at
     * a quarter of it above, a superscript or a subscript at two thirds of the size, raised a third
     * or lowered a seventh of the em.
     *
     * @param size the em size, in logical points
     */
    public static TextDecorations fallback(float size) {
        float thickness = Math.max(1f, size / 14f);
        return new TextDecorations(size * 0.1f, thickness, size * 0.25f, thickness,
                size * 0.34f, size * 0.65f, size * 0.14f, size * 0.65f);
    }
}
