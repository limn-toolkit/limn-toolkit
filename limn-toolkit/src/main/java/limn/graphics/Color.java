package limn.graphics;

/**
 * Immutable straight-alpha RGBA color, channels in {@code [0..1]}.
 *
 * @param r red
 * @param g green
 * @param b blue
 * @param a alpha (1 = opaque)
 */
public record Color(float r, float g, float b, float a) implements Paint {

    public static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);
    public static final Color BLACK = new Color(0f, 0f, 0f, 1f);
    public static final Color WHITE = new Color(1f, 1f, 1f, 1f);

    public Color {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
        a = clamp(a);
    }

    /** Opaque color from a {@code 0xRRGGBB} value. */
    public static Color rgb(int rgb) {
        return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
    }

    /** Color from a {@code 0xRRGGBB} value plus explicit alpha. */
    public static Color rgba(int rgb, float alpha) {
        Color base = rgb(rgb);
        return new Color(base.r, base.g, base.b, alpha);
    }

    /** @return this color with its alpha replaced by {@code alpha} */
    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }

    /** Linear interpolation between this color and {@code other} at {@code t} in [0..1]. */
    public Color lerp(Color other, float t) {
        float k = clamp(t);
        return new Color(
                r + (other.r - r) * k,
                g + (other.g - g) * k,
                b + (other.b - b) * k,
                a + (other.a - a) * k);
    }

    // ------------------------------------------------------------------ HSV
    //
    // The axes people actually reason about: "the same blue but paler" is one
    // move in HSV and three in RGB, which is why every colour picker is built on
    // them. Kept here rather than in the picker so the conversion is testable on
    // its own and reusable by anything that needs to shift a hue.
    //
    // HSV is the same model Photoshop and the macOS picker call HSB: Brightness
    // and Value are two names for the third axis, not two axes. HSL is a DIFFERENT
    // model and is deliberately not offered: its saturation is not this one (they
    // agree only at the extremes), so a picker showing both makes the numbers jump
    // when you switch between them, which reads as the picker losing the colour.
    // Engines speak HSV (Unity, Unreal, Godot, Krita, Aseprite); HSL is CSS's.

    /**
     * From hue/saturation/value. {@code hue} is in degrees and wraps, so 370 and
     * -350 both mean 10; {@code saturation}, {@code value} and {@code alpha} are
     * clamped to [0,1].
     */
    public static Color hsv(float hue, float saturation, float value, float alpha) {
        float h = ((hue % 360f) + 360f) % 360f / 60f;
        float s = clamp(saturation);
        float v = clamp(value);
        int sector = (int) Math.floor(h);
        float f = h - sector;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        return switch (sector % 6) {
            case 0 -> new Color(v, t, p, alpha);
            case 1 -> new Color(q, v, p, alpha);
            case 2 -> new Color(p, v, t, alpha);
            case 3 -> new Color(p, q, v, alpha);
            case 4 -> new Color(t, p, v, alpha);
            default -> new Color(v, p, q, alpha);
        };
    }

    /**
     * Hue in degrees [0,360). Grey has no hue and answers 0; a picker must keep
     * its own hue across a trip through grey, or dragging saturation to zero would
     * lose which colour you were on.
     */
    public float hue() {
        float max = Math.max(r, Math.max(g, b));
        float chroma = max - Math.min(r, Math.min(g, b));
        if (chroma <= 0) {
            return 0;
        }
        float h;
        if (max == r) {
            h = (g - b) / chroma;
        } else if (max == g) {
            h = 2 + (b - r) / chroma;
        } else {
            h = 4 + (r - g) / chroma;
        }
        return ((h * 60f) % 360f + 360f) % 360f;
    }

    /** Saturation in [0,1]: how far from grey. */
    public float saturation() {
        float max = Math.max(r, Math.max(g, b));
        return max <= 0 ? 0 : (max - Math.min(r, Math.min(g, b))) / max;
    }

    /** Value in [0,1]: the brightest channel. */
    public float value() {
        return Math.max(r, Math.max(g, b));
    }

    // ----------------------------------------------------------------- CMYK

    /**
     * From cyan/magenta/yellow/key, each in [0,1].
     *
     * <p><b>Naive, not colour-managed.</b> This is the plain arithmetic conversion
     * every colour picker offers, and it is a convenience for people who think in
     * ink percentages, not a substitute for a profile. Print work needs one; this
     * will not match a press.
     */
    public static Color cmyk(float cyan, float magenta, float yellow, float key, float alpha) {
        float k = clamp(key);
        return new Color((1 - clamp(cyan)) * (1 - k), (1 - clamp(magenta)) * (1 - k),
                (1 - clamp(yellow)) * (1 - k), alpha);
    }

    /** Cyan, magenta, yellow and key in [0,1]: the inverse of {@link #cmyk}. */
    public float[] toCmyk(float[] out) {
        float k = 1 - Math.max(r, Math.max(g, b));
        float scale = 1 - k;
        out[0] = scale <= 0 ? 0 : (scale - r) / scale;
        out[1] = scale <= 0 ? 0 : (scale - g) / scale;
        out[2] = scale <= 0 ? 0 : (scale - b) / scale;
        out[3] = k;
        return out;
    }

    // ------------------------------------------------------------------ hex

    /** {@code "#RRGGBB"}, or {@code "#RRGGBBAA"} when not fully opaque. */
    public String toHex() {
        String rgb = String.format("#%02X%02X%02X", byteOf(r), byteOf(g), byteOf(b));
        return a >= 1f ? rgb : rgb + String.format("%02X", byteOf(a));
    }

    /**
     * Parses {@code #RGB}, {@code #RGBA}, {@code #RRGGBB} or {@code #RRGGBBAA},
     * with or without the {@code #}. Missing alpha is opaque.
     *
     * @return the colour, or {@code null} if the text is not one of those forms:
     *         a field being typed into is half-written most of the time, and an
     *         exception per keystroke is not a parser, it is a nuisance
     */
    public static Color fromHex(String text) {
        if (text == null) {
            return null;
        }
        String hex = text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        int length = hex.length();
        if (length != 3 && length != 4 && length != 6 && length != 8) {
            return null;
        }
        boolean shorthand = length <= 4;
        int digits = shorthand ? 1 : 2;
        float[] channels = {0, 0, 0, 1};
        for (int i = 0; i < length / digits; i++) {
            String part = hex.substring(i * digits, i * digits + digits);
            int v;
            try {
                v = Integer.parseInt(part, 16);
            } catch (NumberFormatException e) {
                return null;
            }
            channels[i] = (shorthand ? v * 17 : v) / 255f; // #abc means #aabbcc
        }
        return new Color(channels[0], channels[1], channels[2], channels[3]);
    }

    // ---------------------------------------------------------- legibility
    //
    // Two scales, because one of them is unusable at the light end. Contrast ratio is
    // what accessibility is specified in and what a reviewer will ask for; L* is what a
    // step of elevation has to be measured in, since the ratio between a near-white
    // canvas and the card on it would require a luminance above 1 to reach the same
    // number a dark palette reaches easily.

    /**
     * WCAG&nbsp;2.1 relative luminance in [0,1]: the sRGB channels linearized and
     * weighted for the eye's response, so 0 is black and 1 is white.
     *
     * <p><b>Alpha is ignored.</b> A translucent colour has no luminance of its own; what
     * a reader sees is the composite. Composite it over its backdrop first
     * ({@code backdrop.lerp(this, this.a())}) and ask that colour.
     */
    public double relativeLuminance() {
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
    }

    /**
     * WCAG&nbsp;2.1 contrast ratio between two colours, from 1 (identical) to 21
     * (black on white). Symmetric: which argument is the ink does not change it.
     *
     * <p>The bars the guideline sets: <b>4.5</b> for body text, <b>3</b> for large text
     * and for meaningful non-text such as an accent-filled indicator or a focus ring,
     * and <b>7</b> for the enhanced (AAA) level.
     *
     * <p><b>Both colours must be opaque</b> for the answer to mean anything; see
     * {@link #relativeLuminance()}.
     */
    public static double contrastRatio(Color a, Color b) {
        double la = a.relativeLuminance();
        double lb = b.relativeLuminance();
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * CIE L* in [0,100]: perceptual lightness, where a difference of 1 is roughly the
     * smallest step a person can see anywhere on the scale.
     *
     * <p>This is the scale to measure <em>elevation</em> on. A contrast ratio cannot
     * describe the step from a surface to the card above it in a light palette at all:
     * the step a dark palette reaches at 1.46:1 would need a luminance above 1 to
     * reproduce from a near-white canvas, so the ratio reports two palettes as wildly
     * different when they read identically.
     *
     * <p>Alpha is ignored, exactly as in {@link #relativeLuminance()}.
     */
    public double lightness() {
        double y = relativeLuminance();
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    /** One sRGB channel undone back to linear light, per WCAG 2.1. */
    private static double linearize(float channel) {
        return channel <= 0.03928f ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static int byteOf(float channel) {
        return Math.round(clamp(channel) * 255f);
    }

    private static float clamp(float v) {
        return Math.min(1f, Math.max(0f, v));
    }
}
