package limn.graphics;

import java.util.Objects;

/**
 * A typeface selection: family name, size in logical points, and weight/style
 * (regular / bold / italic / bold-italic). The backend resolves the
 * {@code (family, isBold, isItalic)} triple against its registered faces
 * ({@code "default"}, and unknown families, map to the embedded Roboto) and
 * rasterizes glyphs at {@code size × contentScale} physical pixels, so text is
 * crisp at any HiDPI scale; glyph bitmaps are never scaled.
 *
 * @param family   typeface family, case-insensitive (ships "Roboto")
 * @param size     em size in logical points (like CSS px)
 * @param isBold   whether to select the bold face
 * @param isItalic whether to select the italic face
 */
public record Font(String family, float size, boolean isBold, boolean isItalic) {

    /** Family alias that always resolves to the toolkit's embedded font. */
    public static final String DEFAULT_FAMILY = "default";

    public Font {
        Objects.requireNonNull(family, "family");
        if (size <= 0 || !Float.isFinite(size)) {
            throw new IllegalArgumentException("font size must be positive and finite, got " + size);
        }
    }

    /** Regular weight/style at the given family and size. */
    public Font(String family, float size) {
        this(family, size, false, false);
    }

    /** The default family at the given size (regular). */
    public static Font of(float size) {
        return new Font(DEFAULT_FAMILY, size, false, false);
    }

    /** @return this font at a different size (weight/style preserved). */
    public Font withSize(float newSize) {
        return new Font(family, newSize, isBold, isItalic);
    }

    /** @return this font in bold. */
    public Font bold() {
        return new Font(family, size, true, isItalic);
    }

    /** @return this font in italic. */
    public Font italic() {
        return new Font(family, size, isBold, true);
    }

    /** @return this font in bold italic. */
    public Font boldItalic() {
        return new Font(family, size, true, true);
    }
}
