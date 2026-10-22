package limn.print.internal;

import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.LinearGradient;
import limn.graphics.Paint;
import limn.graphics.RadialGradient;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A document's PDF, page by page: each recorded page replayed into a {@link PdfCanvas}, its content
 * stream and the objects it names written as they come, and the fonts, the page tree and the
 * catalog written at the end.
 *
 * <p>PDF 1.7 in DeviceRGB, the toolkit's colours being sRGB. The information dictionary carries the
 * title and "Limn" as the producer and no date, so the same pages give the same bytes; the catalog
 * carries the language of the document's locale.
 *
 * <p>A page is written in the memory of that page and of what it shares with others: once
 * {@link #page} returns, its content is in the file and nothing of it is kept but the number of its
 * page object. Any thread, one at a time: nothing here asks the backend anything, the glyphs having
 * been kept in a {@link GlyphBank} while the pages were recorded.
 */
public final class PdfWriter {

    private final PdfFile file;
    private final PdfFonts fonts;
    private final GlyphBank glyphs;
    private final String title;
    private final @Nullable Locale language;
    private final float rasterScale;
    private final int pagesNumber;
    private final List<Integer> pages = new ArrayList<>();

    /** Every named resource, by its name: the reference a resources dictionary gives it. */
    private final Map<String, Integer> resources = new HashMap<>();
    private final Map<String, String> states = new HashMap<>();
    private final Map<Image, String> images = new IdentityHashMap<>();
    private final Map<Image, String> smoothImages = new IdentityHashMap<>();
    private final Map<Image, Map<Color, String>> tinted = new IdentityHashMap<>();
    private final Map<Image, Map<Color, String>> masks = new IdentityHashMap<>();
    private final Map<Paint, String> shadings = new HashMap<>();
    private int counter;

    /**
     * @param out         where the file goes; not closed
     * @param glyphs      the outlines kept while the pages were recorded
     * @param title       the document's title, written in its information dictionary
     * @param language    the document's language, or null for none
     * @param rasterScale pixels per document unit for what is drawn as a picture
     */
    public PdfWriter(OutputStream out, GlyphBank glyphs, String title, @Nullable Locale language,
                     float rasterScale) throws IOException {
        this.file = new PdfFile(out);
        this.glyphs = glyphs;
        this.fonts = new PdfFonts(file, glyphs);
        this.title = title;
        this.language = language;
        this.rasterScale = rasterScale;
        this.pagesNumber = file.reserve();
    }

    /**
     * Writes one page.
     *
     * @param page    the page's drawing, in document units (1/96 inch), y down
     * @param widthPt the paper's width in points
     * @param heightPt its height in points
     */
    public void page(Recording page, float widthPt, float heightPt) throws IOException {
        PdfCanvas canvas = new PdfCanvas(this, page.width(), page.height(), widthPt, heightPt);
        page.replay(canvas);
        PdfCanvas.Content content = canvas.finish();
        int stream = file.stream("", content.ops().getBytes(StandardCharsets.ISO_8859_1), true);
        int number = file.reserve();
        file.object(number, "<</Type/Page/Parent " + pagesNumber + " 0 R/MediaBox[0 0 " + Pdf.num(widthPt)
                + " " + Pdf.num(heightPt) + "]/Resources" + resourceDictionary(content.used())
                + "/Contents " + stream + " 0 R/Group<</S/Transparency/CS/DeviceRGB>>>>");
        pages.add(number);
    }

    /** Ends the document: the fonts, the page tree, the catalog, the information and the table. */
    public void finish() throws IOException {
        fonts.finish();
        StringBuilder kids = new StringBuilder();
        for (int page : pages) {
            kids.append(kids.length() == 0 ? "" : " ").append(page).append(" 0 R");
        }
        file.object(pagesNumber, "<</Type/Pages/Kids[" + kids + "]/Count " + pages.size() + ">>");
        String lang = language == null || language.toLanguageTag().equals("und") ? ""
                : "/Lang" + Pdf.text(language.toLanguageTag());
        int catalog = file.object("<</Type/Catalog/Pages " + pagesNumber + " 0 R" + lang + ">>");
        int info = file.object("<</Title" + Pdf.text(title) + "/Producer" + Pdf.text("Limn") + ">>");
        file.finish(catalog, info);
    }

    // ------------------------------------------------------------------ for the canvas

    PdfFonts fonts() {
        return fonts;
    }

    GlyphBank glyphs() {
        return glyphs;
    }

    float rasterScale() {
        return rasterScale;
    }

    /** @return a graphics state that sets the fill and stroke alpha and the blend mode */
    String state(float alpha, BlendMode blend) throws IOException {
        String key = Pdf.num(alpha) + "/" + blend;
        String name = states.get(key);
        if (name == null) {
            String mode = switch (blend) {
                case MULTIPLY -> "/Multiply";
                // PDF has no linear add; Screen is the nearest of its separable modes.
                case ADDITIVE -> "/Screen";
                default -> "/Normal";
            };
            name = "GS" + (++counter);
            resources.put(name, file.object("<</Type/ExtGState/ca " + Pdf.num(alpha) + "/CA " + Pdf.num(alpha)
                    + "/BM" + mode + ">>"));
            states.put(key, name);
        }
        return name;
    }

    /**
     * A graphics state whose soft mask is a gradient of alpha, for a gradient whose two colours are
     * not equally opaque: the mask is the alpha ramp drawn as grey in the user space in force.
     */
    String maskedState(Paint gradient, float startAlpha, float endAlpha, BlendMode blend) throws IOException {
        String shading = shading(gradient, new Color(startAlpha, startAlpha, startAlpha, 1),
                new Color(endAlpha, endAlpha, endAlpha, 1), "/DeviceGray");
        int group = file.stream("/Type/XObject/Subtype/Form/BBox[-100000 -100000 100000 100000]"
                        + "/Group<</S/Transparency/CS/DeviceGray>>/Resources<</Shading<</" + shading + " "
                        + resources.get(shading) + " 0 R>>>>",
                ("/" + shading + " sh").getBytes(StandardCharsets.ISO_8859_1), true);
        String name = "GS" + (++counter);
        String mode = blend == BlendMode.MULTIPLY ? "/Multiply" : blend == BlendMode.ADDITIVE ? "/Screen" : "/Normal";
        resources.put(name, file.object("<</Type/ExtGState/BM" + mode + "/SMask<</S/Luminosity/G " + group
                + " 0 R>>>>"));
        return name;
    }

    /** @return a shading of the gradient's own colours, opaque, in the user space it is drawn in */
    String shading(Paint gradient) throws IOException {
        String name = shadings.get(gradient);
        if (name == null) {
            Color start = gradient instanceof LinearGradient l ? l.start() : ((RadialGradient) gradient).center();
            Color end = gradient instanceof LinearGradient l ? l.end() : ((RadialGradient) gradient).edge();
            name = shading(gradient, start, end, "/DeviceRGB");
            shadings.put(gradient, name);
        }
        return name;
    }

    private String shading(Paint gradient, Color start, Color end, String space) throws IOException {
        boolean gray = space.equals("/DeviceGray");
        String c0 = gray ? Pdf.num(start.r()) : rgb(start);
        String c1 = gray ? Pdf.num(end.r()) : rgb(end);
        String function = "<</FunctionType 2/Domain[0 1]/C0[" + c0 + "]/C1[" + c1 + "]/N 1>>";
        String body;
        if (gradient instanceof LinearGradient l) {
            body = "<</ShadingType 2/ColorSpace" + space + "/Coords[" + Pdf.num(l.x0()) + " " + Pdf.num(l.y0())
                    + " " + Pdf.num(l.x1()) + " " + Pdf.num(l.y1()) + "]/Function" + function
                    + "/Extend[true true]>>";
        } else {
            RadialGradient r = (RadialGradient) gradient;
            body = "<</ShadingType 3/ColorSpace" + space + "/Coords[" + Pdf.num(r.cx()) + " " + Pdf.num(r.cy())
                    + " 0 " + Pdf.num(r.cx()) + " " + Pdf.num(r.cy()) + " " + Pdf.num(r.radius()) + "]/Function"
                    + function + "/Extend[true true]>>";
        }
        String name = "Sh" + (++counter);
        resources.put(name, file.object(body));
        return name;
    }

    private static String rgb(Color color) {
        return Pdf.num(color.r()) + " " + Pdf.num(color.g()) + " " + Pdf.num(color.b());
    }

    /** @return a pattern painting the gradient, placed by {@code matrix} from the pattern space */
    String pattern(Paint gradient, Affine matrix) throws IOException {
        String shading = shading(gradient);
        String name = "P" + (++counter);
        resources.put(name, file.object("<</PatternType 2/Shading " + resources.get(shading) + " 0 R/Matrix["
                + Pdf.num(matrix.a()) + " " + Pdf.num(matrix.b()) + " " + Pdf.num(matrix.c()) + " "
                + Pdf.num(matrix.d()) + " " + Pdf.num(matrix.e()) + " " + Pdf.num(matrix.f()) + "]>>"));
        return name;
    }

    /** @return an image's XObject, written once per image and sampling */
    String image(Image image, boolean smooth) throws IOException {
        Map<Image, String> cache = smooth ? smoothImages : images;
        String name = cache.get(image);
        if (name == null) {
            name = writeImage(image.width(), image.height(), image.pixels(), smooth);
            cache.put(image, name);
        }
        return name;
    }

    /** @return the image with its colour and alpha multiplied by {@code tint} */
    String tinted(Image image, Color tint, boolean smooth) throws IOException {
        Map<Color, String> byTint = tinted.computeIfAbsent(image, i -> new HashMap<>());
        String name = byTint.get(tint);
        if (name == null) {
            byte[] src = image.pixels();
            byte[] out = new byte[src.length];
            for (int i = 0; i + 3 < src.length; i += 4) {
                out[i] = (byte) Math.round((src[i] & 0xFF) * tint.r());
                out[i + 1] = (byte) Math.round((src[i + 1] & 0xFF) * tint.g());
                out[i + 2] = (byte) Math.round((src[i + 2] & 0xFF) * tint.b());
                out[i + 3] = (byte) Math.round((src[i + 3] & 0xFF) * tint.a());
            }
            name = writeImage(image.width(), image.height(), out, smooth);
            byTint.put(tint, name);
        }
        return name;
    }

    /** @return the image's alpha as coverage, painted in the tint's colour and alpha */
    String mask(Image image, Color tint, boolean smooth) throws IOException {
        Map<Color, String> byTint = masks.computeIfAbsent(image, i -> new HashMap<>());
        String name = byTint.get(tint);
        if (name == null) {
            byte[] src = image.pixels();
            byte[] alpha = new byte[image.width() * image.height()];
            for (int i = 0; i < alpha.length; i++) {
                alpha[i] = (byte) Math.round((src[i * 4 + 3] & 0xFF) * tint.a());
            }
            name = coverage(image.width(), image.height(), alpha, tint, smooth);
            byTint.put(tint, name);
        }
        return name;
    }

    /**
     * @return a picture of one colour whose coverage is {@code alpha}, a byte per pixel: a mask's,
     *         a rasterized shadow's
     */
    String coverage(int width, int height, byte[] alpha, Color color, boolean smooth) throws IOException {
        int soft = file.stream("/Type/XObject/Subtype/Image/Width " + width + "/Height " + height
                + "/ColorSpace/DeviceGray/BitsPerComponent 8" + (smooth ? "/Interpolate true" : ""), alpha, true);
        byte[] one = {(byte) Color.toByte(color.r()), (byte) Color.toByte(color.g()), (byte) Color.toByte(color.b())};
        String name = "Im" + (++counter);
        resources.put(name, file.stream("/Type/XObject/Subtype/Image/Width 1/Height 1/ColorSpace/DeviceRGB"
                + "/BitsPerComponent 8/SMask " + soft + " 0 R", one, true));
        return name;
    }

    private String writeImage(int width, int height, byte[] rgba, boolean smooth) throws IOException {
        byte[] rgb = new byte[width * height * 3];
        byte[] alpha = new byte[width * height];
        boolean translucent = false;
        for (int i = 0, p = 0; i < alpha.length; i++, p += 4) {
            rgb[i * 3] = rgba[p];
            rgb[i * 3 + 1] = rgba[p + 1];
            rgb[i * 3 + 2] = rgba[p + 2];
            alpha[i] = rgba[p + 3];
            translucent |= alpha[i] != (byte) 0xFF;
        }
        String common = "/Type/XObject/Subtype/Image/Width " + width + "/Height " + height + "/BitsPerComponent 8"
                + (smooth ? "/Interpolate true" : "");
        String smask = "";
        if (translucent) {
            smask = "/SMask " + file.stream(common + "/ColorSpace/DeviceGray", alpha, true) + " 0 R";
        }
        String name = "Im" + (++counter);
        resources.put(name, file.stream(common + "/ColorSpace/DeviceRGB" + smask, rgb, true));
        return name;
    }

    /**
     * Writes a layer's content as a transparency group.
     *
     * @return the form's resource name
     */
    String form(PdfCanvas.Content content, float x, float y, float width, float height) throws IOException {
        String name = "Fm" + (++counter);
        resources.put(name, file.stream("/Type/XObject/Subtype/Form/BBox[" + Pdf.num(x) + " " + Pdf.num(y) + " "
                        + Pdf.num(x + width) + " " + Pdf.num(y + height) + "]"
                        + "/Group<</S/Transparency/CS/DeviceRGB/I true>>"
                        + "/Resources" + resourceDictionary(content.used()),
                content.ops().getBytes(StandardCharsets.ISO_8859_1), true));
        return name;
    }

    /** A resources dictionary naming {@code used}, each in its category by the prefix of its name. */
    private String resourceDictionary(Set<String> used) {
        Map<String, StringBuilder> byKind = new HashMap<>();
        for (String name : new TreeSet<>(used)) {
            String kind;
            Integer number;
            if (name.startsWith("F") && !name.startsWith("Fm")) {
                kind = "Font";
                number = fontNumber(name);
            } else {
                kind = name.startsWith("GS") ? "ExtGState" : name.startsWith("Sh") ? "Shading"
                        : name.startsWith("P") ? "Pattern" : "XObject";
                number = resources.get(name);
            }
            if (number == null) {
                continue;
            }
            byKind.computeIfAbsent(kind, k -> new StringBuilder()).append('/').append(name).append(' ')
                    .append(number).append(" 0 R");
        }
        StringBuilder dict = new StringBuilder("<<");
        for (String kind : List.of("ExtGState", "Font", "Pattern", "Shading", "XObject")) {
            StringBuilder entries = byKind.get(kind);
            if (entries != null) {
                dict.append('/').append(kind).append("<<").append(entries).append(">>");
            }
        }
        return dict.append(">>").toString();
    }

    private @Nullable Integer fontNumber(String name) {
        for (PdfFonts.Subset subset : fonts.fonts()) {
            if (subset.resource.equals(name)) {
                return subset.number;
            }
        }
        return null;
    }
}
