package limn.print;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A sheet of paper: its name and its size in points (1/72 inch), portrait or landscape.
 *
 * <pre>{@code
 * Document.of(Paper.A4);
 * Document.of(Paper.LETTER.landscape());
 * Document.of(Paper.of("Receipt", 80, 200));            // millimetres
 * Document.of(Paper.forLocale(Locale.getDefault()));
 * }</pre>
 *
 * @param name   what a person calls it; never blank
 * @param width  its width in points, more than zero
 * @param height its height in points, more than zero
 */
public record Paper(String name, float width, float height) {

    /** Points in a millimetre. */
    private static final float MM = 72f / 25.4f;

    /** ISO A3, 297 by 420 millimetres. */
    public static final Paper A3 = ofMillimetres("A3", 297, 420);
    /** ISO A4, 210 by 297 millimetres. */
    public static final Paper A4 = ofMillimetres("A4", 210, 297);
    /** ISO A5, 148 by 210 millimetres. */
    public static final Paper A5 = ofMillimetres("A5", 148, 210);
    /** ISO A6, 105 by 148 millimetres. */
    public static final Paper A6 = ofMillimetres("A6", 105, 148);
    /** ISO B4, 250 by 353 millimetres. */
    public static final Paper B4 = ofMillimetres("B4", 250, 353);
    /** ISO B5, 176 by 250 millimetres. */
    public static final Paper B5 = ofMillimetres("B5", 176, 250);
    /** Letter, 8.5 by 11 inches. */
    public static final Paper LETTER = new Paper("Letter", 612, 792);
    /** Legal, 8.5 by 14 inches. */
    public static final Paper LEGAL = new Paper("Legal", 612, 1008);
    /** Tabloid, 11 by 17 inches. */
    public static final Paper TABLOID = new Paper("Tabloid", 792, 1224);

    /**
     * The regions whose paper is Letter, as the Unicode common locale data lists them; every other
     * region uses A4.
     */
    private static final Set<String> LETTER_REGIONS = Set.of("US", "CA", "MX", "PH", "PR", "CL", "CO",
            "CR", "GT", "NI", "PA", "SV", "VE", "BZ");

    public Paper {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a paper has a name");
        }
        if (!(width > 0) || !(height > 0) || !Float.isFinite(width) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("paper " + width + " x " + height + " points");
        }
    }

    /**
     * @param name   what a person calls it
     * @param width  its width in millimetres
     * @param height its height in millimetres
     * @return the paper
     */
    public static Paper of(String name, float width, float height) {
        return ofMillimetres(name, width, height);
    }

    /**
     * @param name   what a person calls it
     * @param width  its width in points
     * @param height its height in points
     * @return the paper
     */
    public static Paper ofPoints(String name, float width, float height) {
        return new Paper(name, width, height);
    }

    private static Paper ofMillimetres(String name, float width, float height) {
        return new Paper(name, width * MM, height * MM);
    }

    /**
     * The paper a region uses: Letter in the regions that use it (the United States, Canada,
     * Mexico, the Philippines and the others the Unicode locale data lists), A4 everywhere else and
     * for a locale that names no region.
     *
     * @param locale a locale
     * @return Letter or A4
     */
    public static Paper forLocale(Locale locale) {
        return LETTER_REGIONS.contains(Objects.requireNonNull(locale, "locale").getCountry()) ? LETTER : A4;
    }

    /** @return this paper turned on its side: the longer side across; itself when it is already */
    public Paper landscape() {
        return width >= height ? this : new Paper(name, height, width);
    }

    /** @return this paper upright: the longer side down; itself when it is already */
    public Paper portrait() {
        return height >= width ? this : new Paper(name, height, width);
    }

    /** @return whether the paper is wider than it is tall */
    public boolean isLandscape() {
        return width > height;
    }

    /** @return the width in millimetres */
    public float widthMillimetres() {
        return width / MM;
    }

    /** @return the height in millimetres */
    public float heightMillimetres() {
        return height / MM;
    }
}
