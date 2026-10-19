package limn.components;

import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Random edits from fixed seeds on masked fields, each checked against a model that knows, from
 * the pattern alone, which characters of the text are literals and which are the value's. What
 * it holds a field to: no edit ever turns a literal into a value, a value character goes only when
 * the user took it (or, on a pattern that mixes digit, letter and any positions, when the close-up
 * after an edit lands it in a position that refuses it), typing into a field with no free position
 * changes nothing, and the field's text is always one its pattern writes, so
 * {@link TextField#isMaskComplete()} is true only when every position holds a character the user
 * or the application supplied. The patterns are the ones the demo uses and the ones edits before a
 * run of literals once corrupted.
 */
class TextFieldMaskEditsTest extends AccessibleComponentTestBase {

    private static final int COMMAND = Accelerator.commandModifier();

    private static final String[] PATTERNS = {
            // The demo's.
            "+1 (000) 000-0000", "AAA-0000-**", "000.000.000-00", "'DE'00 0000 0000 0000 0000 00",
            "0000 0000 0000 0000", "AAA-0000", "00.000.000/0000",
            // Runs a position would also take, and the shapes around them.
            "+55 (00) 00000-0000", "****-****", "000 '9' 000", "'55'0-000", "+'0' 000", "(00)",
            "00 kg", "**.**", "''00", "AAA-AAA",
    };

    private static final int RUNS = 24;
    private static final int STEPS = 60;

    private TextField field;
    private final TextFieldTest.MockClipboard clipboard = new TextFieldTest.MockClipboard();

    @Test
    void noEditTurnsALiteralIntoAValueOrLosesAValueTheUserDidNotTake() {
        for (String pattern : PATTERNS) {
            int[] reached = new int[2];
            for (int run = 0; run < RUNS; run++) {
                exercise(pattern, 0x5EED_0000L + pattern.hashCode() * 31L + run, reached);
            }
            // The hard cases, not only the easy one: edits with value on both sides of them, and
            // insertions into a field with no position free.
            if (reached[0] < 20 || reached[1] < 5) {
                fail(pattern + ": the random edits reached " + reached[0] + " edits mid-value and "
                        + reached[1] + " insertions into a full field; more runs or steps are needed");
            }
        }
    }

    private void exercise(String pattern, long seed, int[] reached) {
        Model model = new Model(pattern);
        field = new TextField().setMask(pattern);
        Column root = new Column();
        root.add(field);
        bind(root);
        scene.setTextRuler(RULER);
        scene.setClipboard(clipboard);
        frame();
        field.requestFocus();
        Random random = new Random(seed);
        Set<String> seen = new HashSet<>();
        List<String> log = new ArrayList<>();
        seen.add(state());
        for (int step = 0; step < STEPS; step++) {
            String oldText = field.text();
            String oldRaw = field.unmaskedText();
            int selStart = field.selectionStart();
            int selEnd = field.selectionEnd();
            int caret = field.caretPosition();
            boolean selected = selStart != selEnd;
            int roll = random.nextInt(100);
            String typed = null;
            int from = selected ? selStart : caret;
            int to = selected ? selEnd : caret;
            int collapsed = 0;
            boolean history = false;
            boolean weak = false;
            String what;
            if (roll < 30) {
                typed = new String(Character.toChars(model.someCharacter(random)));
                what = "type " + typed;
                drive(scene).type(typed);
            } else if (roll < 40) {
                typed = model.someFragment(random);
                what = "paste " + typed;
                clipboard.value = typed;
                drive(scene).press(Keys.V, COMMAND);
                if (typed.isEmpty()) {
                    from = to = -1; // an empty clipboard is no edit
                }
            } else if (roll < 50) {
                what = "backspace";
                if (!selected) {
                    collapsed = -1;
                    from = caret == 0 ? caret : oldText.offsetByCodePoints(caret, -1);
                }
                drive(scene).press(Keys.BACKSPACE);
            } else if (roll < 57) {
                what = "delete";
                if (!selected) {
                    collapsed = 1;
                    to = caret == oldText.length() ? caret : oldText.offsetByCodePoints(caret, 1);
                }
                drive(scene).press(Keys.DELETE);
            } else if (roll < 60) {
                what = "word backspace";
                weak = true;
                drive(scene).press(Keys.BACKSPACE, Keys.MOD_ALT);
            } else if (roll < 66) {
                what = "undo";
                history = true;
                drive(scene).press(Keys.Z, COMMAND);
            } else if (roll < 70) {
                what = "redo";
                history = true;
                drive(scene).press(Keys.Z, COMMAND | Keys.MOD_SHIFT);
            } else if (roll < 74) {
                String value = random.nextBoolean() ? model.someValue(random)
                        : model.someFragment(random) + model.someFragment(random);
                what = "setText " + value;
                field.setText(value);
                seen.clear(); // the history is gone with it
                from = to = -1;
            } else {
                what = move(random, oldText);
                from = to = -1;
            }
            log.add(what + " -> " + field.text() + " [" + field.unmaskedText() + "] caret "
                    + field.caretPosition());
            String context = pattern + ", seed " + Long.toHexString(seed) + ", step " + step + " ("
                    + what + ") on \"" + oldText + "\" caret " + caret + " selection " + selStart
                    + ".." + selEnd + "\n  " + String.join("\n  ", log);
            model.checkWritten(field, context);
            if (history) {
                if (!seen.contains(state())) {
                    fail("an undo or redo reached a state no edit made: " + context);
                }
            } else if (weak) {
                if (!isSubsequence(field.unmaskedText(), oldRaw)) {
                    fail("a word deletion added a value character: " + context);
                }
            } else if (from >= 0) {
                model.count(oldText, from, to, typed, reached);
                model.checkEdit(oldText, oldRaw, from, to, typed == null ? "" : typed, collapsed,
                        field, context);
            } else if (!what.startsWith("setText") && !(field.text().equals(oldText)
                    && field.unmaskedText().equals(oldRaw))) {
                fail("a caret move changed the text: " + context);
            }
            seen.add(state());
            if (step % 7 == 0) {
                frame(); // the guide and the caret drawn over every shape the text takes
            }
        }
    }

    private String state() {
        return field.text() + '\u0000' + field.unmaskedText();
    }

    private String move(Random random, String text) {
        int count = text.codePointCount(0, text.length());
        switch (random.nextInt(8)) {
            case 0 -> {
                drive(scene).press(Keys.HOME);
                return "home";
            }
            case 1 -> {
                drive(scene).press(Keys.END);
                return "end";
            }
            case 2 -> {
                drive(scene).press(Keys.LEFT);
                return "left";
            }
            case 3 -> {
                drive(scene).press(Keys.RIGHT);
                return "right";
            }
            case 4 -> {
                drive(scene).press(Keys.LEFT, Keys.MOD_SHIFT);
                return "shift left";
            }
            case 5 -> {
                int a = text.offsetByCodePoints(0, random.nextInt(count + 1));
                int b = text.offsetByCodePoints(0, random.nextInt(count + 1));
                field.select(a, b);
                return "select " + a + ".." + b;
            }
            default -> {
                int at = text.offsetByCodePoints(0, random.nextInt(count + 1));
                field.setCaretPosition(at);
                return "caret " + at;
            }
        }
    }

    private static boolean isSubsequence(String part, String whole) {
        int at = 0;
        for (int i = 0; i < part.length() && at <= whole.length(); ) {
            int cp = part.codePointAt(i);
            int found = whole.indexOf(new String(Character.toChars(cp)), at);
            if (found < 0) {
                return false;
            }
            at = found + Character.charCount(cp);
            i += Character.charCount(cp);
        }
        return true;
    }

    /** The pattern, parsed here on its own, and what an edit may and may not do to a value. */
    private static final class Model {

        private final int[] entries;
        private final boolean[] slot;
        private final int slots;
        private final boolean oneKind;
        private final Set<Integer> literals = new HashSet<>();
        private final int[] alphabet;

        Model(String pattern) {
            int[] given = pattern.codePoints().toArray();
            List<Integer> cps = new ArrayList<>();
            List<Boolean> slotAt = new ArrayList<>();
            boolean quoted = false;
            for (int i = 0; i < given.length; i++) {
                if (given[i] == '\'') {
                    if (i + 1 < given.length && given[i + 1] == '\'') {
                        cps.add((int) '\'');
                        slotAt.add(false);
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                    continue;
                }
                cps.add(given[i]);
                slotAt.add(!quoted && (given[i] == '0' || given[i] == 'A' || given[i] == '*'));
            }
            entries = cps.stream().mapToInt(Integer::intValue).toArray();
            slot = new boolean[entries.length];
            int count = 0;
            int kind = -1;
            boolean mixed = false;
            for (int i = 0; i < entries.length; i++) {
                slot[i] = slotAt.get(i);
                if (slot[i]) {
                    count++;
                    mixed |= kind >= 0 && kind != entries[i];
                    kind = entries[i];
                } else {
                    literals.add(entries[i]);
                }
            }
            slots = count;
            oneKind = !mixed;
            Set<Integer> chars = new java.util.LinkedHashSet<>();
            "0123456789012345abcXYZ".codePoints().forEach(chars::add);
            chars.add(0x05D0); // Hebrew alef and bet: letters written right to left
            chars.add(0x05D1);
            chars.add(0x0663); // an Arabic-Indic three: a digit
            chars.add(0x1F600); // outside the Basic Multilingual Plane
            " -.()+/'".codePoints().forEach(chars::add);
            chars.addAll(literals);
            alphabet = chars.stream().mapToInt(Integer::intValue).toArray();
        }

        int someCharacter(Random random) {
            return alphabet[random.nextInt(alphabet.length)];
        }

        /** A complete value, formatted or as its characters alone. */
        String someValue(Random random) {
            StringBuilder out = new StringBuilder();
            boolean formatted = random.nextBoolean();
            for (int i = 0; i < entries.length; i++) {
                if (slot[i]) {
                    out.appendCodePoint(fill(entries[i], random));
                } else if (formatted) {
                    out.appendCodePoint(entries[i]);
                }
            }
            return out.toString();
        }

        String someFragment(Random random) {
            StringBuilder out = new StringBuilder();
            int length = random.nextInt(6);
            if (random.nextInt(4) == 0) {
                // A piece of the pattern's own text, literals included, as a value copied from
                // elsewhere would carry them.
                int start = random.nextInt(entries.length);
                for (int i = start; i < Math.min(entries.length, start + length + 1); i++) {
                    out.appendCodePoint(slot[i] ? fill(entries[i], random) : entries[i]);
                }
                return out.toString();
            }
            for (int i = 0; i < length; i++) {
                out.appendCodePoint(someCharacter(random));
            }
            return out.toString();
        }

        private static int fill(int kind, Random random) {
            return switch (kind) {
                case '0' -> '0' + random.nextInt(10);
                case 'A' -> 'a' + random.nextInt(26);
                default -> "xyz#9".charAt(random.nextInt(5));
            };
        }

        private static boolean accepts(int kind, int cp) {
            if (Character.isISOControl(cp)) {
                return false;
            }
            return switch (kind) {
                case '0' -> Character.isDigit(cp);
                case 'A' -> Character.isLetter(cp);
                default -> true;
            };
        }

        /** The value characters of a text the pattern wrote, in entries {@code [from, to)}. */
        private String valueIn(String text, int from, int to) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            for (int k = 0; k < text.length(); i++) {
                int cp = text.codePointAt(k);
                if (i >= from && i < to && slot[i]) {
                    out.appendCodePoint(cp);
                }
                k += Character.charCount(cp);
            }
            return out.toString();
        }

        /** Counts an edit with value on both sides, and an insertion into a full field. */
        void count(String oldText, int from, int to, @org.jspecify.annotations.Nullable String typed,
                   int[] reached) {
            int a = oldText.codePointCount(0, from);
            int b = a + oldText.codePointCount(from, to);
            if (!valueIn(oldText, 0, a).isEmpty() && !valueIn(oldText, b, entries.length).isEmpty()
                    || a == 0 && !valueIn(oldText, b, entries.length).isEmpty()) {
                reached[0]++;
            }
            if (typed != null && !typed.isEmpty() && a == b
                    && count(valueIn(oldText, 0, entries.length)) == slots) {
                reached[1]++;
            }
        }

        private static int count(String s) {
            return s.codePointCount(0, s.length());
        }

        /**
         * The text is one the pattern writes: code point {@code i} is entry {@code i}, a literal
         * as itself and a position by a character it takes, every position before the text's end
         * filled, and a complete value with the literals that end the pattern; the value the field
         * reports is exactly the characters in its positions.
         */
        void checkWritten(TextField field, String context) {
            String text = field.text();
            int i = 0;
            for (int k = 0; k < text.length(); i++) {
                int cp = text.codePointAt(k);
                if (i >= entries.length) {
                    fail("the text runs past the pattern: " + context);
                }
                if (slot[i] ? !accepts(entries[i], cp) : cp != entries[i]) {
                    fail("entry " + i + " of \"" + text + "\" is not the pattern's: " + context);
                }
                k += Character.charCount(cp);
            }
            String raw = valueIn(text, 0, entries.length);
            if (!raw.equals(field.unmaskedText())) {
                fail("the value reported (" + field.unmaskedText() + ") is not the one in the positions ("
                        + raw + "): " + context);
            }
            boolean complete = count(raw) == slots;
            if (complete != field.isMaskComplete()) {
                fail("isMaskComplete() is " + field.isMaskComplete() + " with " + count(raw) + " of "
                        + slots + " positions filled: " + context);
            }
            if (complete && i != entries.length) {
                fail("a complete value without the literals that end the pattern: " + context);
            }
            int caret = field.caretPosition();
            if (caret < 0 || caret > text.length()) {
                fail("the caret is outside the text: " + context);
            }
        }

        /**
         * The edit replaced chars {@code [from, to)} of {@code oldText} with {@code typed}. The
         * value after it is the value before {@code from}, then characters of {@code typed}, then
         * the value after {@code to}: never a literal of the old text. On a pattern of one kind of
         * position that is exact, and the typed characters are all a free position takes, in order;
         * on a mixed pattern the close-up may pass over later characters, and only those.
         */
        void checkEdit(String oldText, String oldRaw, int from, int to, String typed, int collapsed,
                       TextField field, String context) {
            int a = oldText.codePointCount(0, from);
            int b = a + oldText.codePointCount(from, to);
            String before = valueIn(oldText, 0, a);
            String removed = valueIn(oldText, a, b);
            String after = valueIn(oldText, b, entries.length);
            String raw = field.unmaskedText();
            String text = field.text();
            int caretValues = count(valueIn(text, 0, text.codePointCount(0, field.caretPosition())));
            if (collapsed != 0 && removed.isEmpty() && typed.isEmpty() && !raw.equals(oldRaw)) {
                // Backspace or Delete took only literals the fitting would put back, so it took the
                // value character beyond them instead.
                String expectedBefore = collapsed < 0 && !before.isEmpty()
                        ? before.substring(0, before.offsetByCodePoints(before.length(), -1)) : before;
                String expectedAfter = collapsed > 0 && !after.isEmpty()
                        ? after.substring(after.offsetByCodePoints(0, 1)) : after;
                expectClosedUp(raw, expectedBefore, "", expectedAfter, context);
                if (caretValues != count(expectedBefore)) {
                    fail("the caret is not where the character taken was: " + context);
                }
                return;
            }
            int room = slots - count(before) - count(after);
            String acceptable = acceptedIn(typed);
            boolean literalTyped = typed.codePoints().anyMatch(literals::contains);
            if (!raw.startsWith(before)) {
                fail("a value character before the edit was lost or changed: " + context);
            }
            String rest = raw.substring(before.length());
            if (oneKind) {
                if (!rest.endsWith(after)) {
                    fail("a value character after the edit was lost or changed, or a literal became one: "
                            + context);
                }
                String inserted = rest.substring(0, rest.length() - after.length());
                if (!isSubsequence(inserted, typed)) {
                    fail("a value character came from neither the value nor what was typed: " + context);
                }
                int expected = Math.min(count(acceptable), Math.max(room, 0));
                if (literalTyped ? count(inserted) > expected : !inserted.equals(
                        acceptable.substring(0, acceptable.offsetByCodePoints(0, expected)))) {
                    fail("the typed characters a free position takes are not the ones inserted ("
                            + inserted + ", room " + room + "): " + context);
                }
                if (caretValues != count(before) + count(inserted)) {
                    fail("the caret is not after what was inserted: " + context);
                }
            } else if (!isSubsequence(rest, typed + after)) {
                fail("a value character came from neither the value nor what was typed: " + context);
            }
        }

        private void expectClosedUp(String raw, String before, String inserted, String after, String context) {
            String expected = before + inserted + after;
            if (oneKind ? !raw.equals(expected) : !(raw.startsWith(before + inserted)
                    && isSubsequence(raw.substring(before.length() + inserted.length()), after))) {
                fail("expected the value " + expected + " after the step over a literal, got " + raw
                        + ": " + context);
            }
        }

        /** The characters of {@code typed} a position of this one-kind pattern takes. */
        private String acceptedIn(String typed) {
            int kind = -1;
            for (int i = 0; i < entries.length; i++) {
                if (slot[i]) {
                    kind = entries[i];
                    break;
                }
            }
            StringBuilder out = new StringBuilder();
            int k = kind;
            typed.codePoints().filter(cp -> accepts(k, cp)).forEach(out::appendCodePoint);
            return out.toString();
        }
    }
}
