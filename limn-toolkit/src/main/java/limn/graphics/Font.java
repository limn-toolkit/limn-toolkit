package limn.graphics;

import java.util.Objects;
import limn.internal.lang.Checks;

/**
 * A typeface selection: family name, size in logical points, weight and slant. The backend
 * resolves the {@code (family, weight, isItalic)} triple against the family's faces
 * ({@code "default"}, and unknown families, map to the embedded Roboto) and rasterizes glyphs at
 * {@code size × contentScale} physical pixels, so text is crisp at any HiDPI scale; glyph bitmaps
 * are never scaled.
 *
 * <p><b>A weight is a number from 1 to 1000</b>, as a style sheet writes it: 100 thin, 300 light,
 * {@value #REGULAR} regular, 500 medium, 600 semibold, {@value #BOLD} bold, 900 black. The face
 * drawn is the family's face nearest it, searched in the order a style sheet's font matching
 * searches (for 400: 400, then 500, then lighter, then heavier; above 500: heavier first, then
 * lighter); a variable face is drawn at the weight itself, within its range. {@link #isBold()}
 * is {@code weight >= 600}, the line a style sheet draws between a medium and a bold.
 *
 * @param family   typeface family, case-insensitive (ships "Roboto")
 * @param size     em size in logical points (like CSS px)
 * @param weight   1 to 1000; {@value #REGULAR} is regular and {@value #BOLD} bold
 * @param isItalic whether to select the italic face
 */
public record Font(String family, float size, int weight, boolean isItalic) {

    /** Family alias that always resolves to the toolkit's embedded font. */
    public static final String DEFAULT_FAMILY = "default";

    /**
     * Family alias for the toolkit's monospace face, every character one width: code, a figure
     * meant to line up in columns. Resolves to the bundled Roboto Mono, which the backend takes
     * from {@code limn-fonts-roboto-mono}; where that artifact is absent, to the default family.
     * Roboto Mono's italic is a little narrower than its upright (0.587 em against 0.6), so text
     * that must keep its columns stays upright.
     */
    public static final String MONOSPACE_FAMILY = "monospace";

    /** The weight of a regular face. */
    public static final int REGULAR = 400;

    /** The weight of a bold face, and what {@code bold()} and a {@code true} bold flag mean. */
    public static final int BOLD = 700;

    public Font {
        Objects.requireNonNull(family, "family");
        Checks.positive(size, "font size");
        if (weight < 1 || weight > 1000) {
            throw new IllegalArgumentException("font weight must be 1 to 1000: " + weight);
        }
    }

    /**
     * A font that is regular or bold: {@value #BOLD} when {@code isBold}, else {@value #REGULAR}.
     *
     * @param family   typeface family, case-insensitive
     * @param size     em size in logical points
     * @param isBold   whether to select the bold face
     * @param isItalic whether to select the italic face
     */
    public Font(String family, float size, boolean isBold, boolean isItalic) {
        this(family, size, isBold ? BOLD : REGULAR, isItalic);
    }

    /** Regular weight/style at the given family and size. */
    public Font(String family, float size) {
        this(family, size, REGULAR, false);
    }

    /** The default family at the given size (regular). */
    public static Font of(float size) {
        return new Font(DEFAULT_FAMILY, size, REGULAR, false);
    }

    /** @return whether this font is bold: a weight of 600 or more */
    public boolean isBold() {
        return weight >= 600;
    }

    /** @return this font at a different size (weight/style preserved). */
    public Font withSize(float newSize) {
        return new Font(family, newSize, weight, isItalic);
    }

    /**
     * @param newWeight 1 to 1000
     * @return this font at a different weight (family, size and slant preserved)
     */
    public Font withWeight(int newWeight) {
        return new Font(family, size, newWeight, isItalic);
    }

    /** @return this font in bold: {@value #BOLD}, or its own weight where that is heavier. */
    public Font bold() {
        return new Font(family, size, Math.max(weight, BOLD), isItalic);
    }

    /** @return this font in italic. */
    public Font italic() {
        return new Font(family, size, weight, true);
    }

    /** @return this font in bold italic, its weight as {@link #bold()} leaves it. */
    public Font boldItalic() {
        return new Font(family, size, Math.max(weight, BOLD), true);
    }
}
