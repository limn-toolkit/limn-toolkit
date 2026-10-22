package limn.print.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The few pieces of PDF syntax every writer here spells the same way. */
final class Pdf {

    private Pdf() {
    }

    /**
     * A number as PDF writes one: no exponent, at most four decimals, no trailing zeros, never
     * "-0", and the same in every locale.
     */
    static String num(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double rounded = Math.round(value * 10000.0) / 10000.0;
        if (rounded == 0) {
            return "0";
        }
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            return Long.toString((long) rounded);
        }
        return BigDecimal.valueOf(rounded).setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
    }

    /** A small number at the precision a font matrix needs: up to eight decimals. */
    static String fine(double value) {
        if (!Double.isFinite(value) || value == 0) {
            return "0";
        }
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
    }

    /** A text string (a title, an alternate text): UTF-16BE with its byte-order mark, in hex. */
    static String text(String value) {
        StringBuilder hex = new StringBuilder(6 + 4 * value.length()).append("<FEFF");
        for (int i = 0; i < value.length(); i++) {
            hex.append(String.format(java.util.Locale.ROOT, "%04X", (int) value.charAt(i)));
        }
        return hex.append('>').toString();
    }

    /** A name, its delimiters and anything outside printable ASCII written as {@code #xx}. */
    static String name(String value) {
        StringBuilder out = new StringBuilder(value.length() + 1).append('/');
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean plain = c > 0x20 && c < 0x7F && "()<>[]{}/%#".indexOf(c) < 0;
            if (plain) {
                out.append((char) c);
            } else {
                out.append('#').append(String.format(java.util.Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }
}
