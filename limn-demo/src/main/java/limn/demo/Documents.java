package limn.demo;

import org.jspecify.annotations.Nullable;
import java.util.Locale;

/**
 * The checks the customer form runs on the numbers it masks: each number carries check digits
 * computed from the rest, so a mistyped digit is caught before anything is sent. Each check takes
 * the text as the field shows it, its separators (dots, dashes, spaces) left in or taken out, and a
 * digit of any script counts as that digit, since a field's {@code 0} position takes one.
 */
final class Documents {

    private Documents() {
    }

    /**
     * Whether {@code text} is a Brazilian CPF: eleven digits whose last two are the check digits of
     * the nine before them, each the remainder of a weighted sum by 11 (weights 10 down to 2 for
     * the first, 11 down to 2 for the second, over the first digit too), taken from 11, and 0 when
     * that remainder is 0 or 1. A number of one repeated digit passes that rule and is refused, as
     * the registry issues none: {@code 000.000.000-00} would otherwise be valid.
     *
     * @param text the number, {@code 123.456.789-09} or {@code 12345678909}
     * @return whether it is a valid CPF
     */
    static boolean cpfValid(String text) {
        int[] d = digits(text, ".- ");
        if (d == null || d.length != 11) {
            return false;
        }
        boolean repeated = true;
        for (int digit : d) {
            repeated &= digit == d[0];
        }
        return !repeated && d[9] == cpfCheck(d, 9) && d[10] == cpfCheck(d, 10);
    }

    /** The check digit of the first {@code count} digits, 9 or 10 of them. */
    private static int cpfCheck(int[] d, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            sum += d[i] * (count + 1 - i);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    /**
     * Whether {@code text} is an IBAN by the check of ISO 13616: two letters of a country, two
     * check digits and the account, 15 to 34 characters in all, which read as one number (its
     * first four characters moved to the end, each letter written as 10 to 35) leaves 1 when divided
     * by 97. The length a country gives its IBANs is not checked, only the check digits.
     *
     * @param text the IBAN, {@code DE89 3704 0044 0532 0130 00} or without its spaces, in either case
     * @return whether its check digits match
     */
    static boolean ibanValid(String text) {
        String iban = text.replace(" ", "").toUpperCase(Locale.ROOT);
        int length = iban.codePointCount(0, iban.length());
        if (length < 15 || length > 34) {
            return false;
        }
        int[] chars = iban.codePoints().toArray();
        if (!isLatinLetter(chars[0]) || !isLatinLetter(chars[1])
                || !Character.isDigit(chars[2]) || !Character.isDigit(chars[3])) {
            return false;
        }
        int remainder = 0;
        for (int i = 0; i < length; i++) {
            int cp = chars[(i + 4) % length];
            int value;
            if (Character.isDigit(cp)) {
                value = Character.digit(cp, 10);
            } else if (isLatinLetter(cp)) {
                value = cp - 'A' + 10;
            } else {
                return false;
            }
            // A letter is two digits of the number, so it shifts the remainder by 100.
            remainder = (remainder * (value < 10 ? 10 : 100) + value) % 97;
        }
        return remainder == 1;
    }

    private static boolean isLatinLetter(int cp) {
        return cp >= 'A' && cp <= 'Z';
    }

    /**
     * Whether {@code text} is a card number whose last digit is its check digit by the Luhn
     * formula: from the right, every second digit doubled (9 taken from it when it passes 9), and
     * the sum of all a multiple of 10.
     *
     * @param text the number, {@code 4111 1111 1111 1111} or without its spaces
     * @return whether its check digit matches
     */
    static boolean luhnValid(String text) {
        int[] d = digits(text, " -");
        if (d == null || d.length < 2) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < d.length; i++) {
            int digit = d[d.length - 1 - i];
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    /**
     * The digits of {@code text}, the characters of {@code separators} passed over, or {@code null}
     * when it holds anything else.
     */
    private static int @Nullable [] digits(String text, String separators) {
        int[] kept = text.codePoints().filter(cp -> separators.indexOf(cp) < 0).toArray();
        for (int i = 0; i < kept.length; i++) {
            if (!Character.isDigit(kept[i])) {
                return null;
            }
            kept[i] = Character.digit(kept[i], 10);
        }
        return kept;
    }
}
