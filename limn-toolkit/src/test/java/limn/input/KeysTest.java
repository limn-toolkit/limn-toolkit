package limn.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The key-code contract: values are GLFW-compatible so the backend maps for
 * free, and letters/digits equal their ASCII codes so {@code Keys.F} and
 * {@code 'F'} can be used interchangeably in application shortcut tables.
 */
class KeysTest {

    @Test
    void lettersAreTheirAsciiValues() {
        assertEquals('A', Keys.A);
        assertEquals('F', Keys.F);
        assertEquals('M', Keys.M);
        assertEquals('Z', Keys.Z);
    }

    @Test
    void digitsAreTheirAsciiValues() {
        assertEquals('0', Keys.NUM_0);
        assertEquals('3', Keys.NUM_3);
        assertEquals('9', Keys.NUM_9);
    }

    @Test
    void punctuationMatchesAscii() {
        assertEquals(' ', Keys.SPACE);
        assertEquals(',', Keys.COMMA);
        assertEquals('.', Keys.PERIOD);
        assertEquals('/', Keys.SLASH);
        assertEquals('[', Keys.LEFT_BRACKET);
        assertEquals(']', Keys.RIGHT_BRACKET);
        assertEquals('\\', Keys.BACKSLASH);
    }

    @Test
    void functionKeysAreContiguousFromGlfwF1() {
        assertEquals(290, Keys.F1);
        for (int i = 0; i < 11; i++) {
            assertEquals(Keys.F1 + i + 1, functionKey(i + 2), "F" + (i + 2));
        }
    }

    private static int functionKey(int number) {
        return switch (number) {
            case 2 -> Keys.F2;
            case 3 -> Keys.F3;
            case 4 -> Keys.F4;
            case 5 -> Keys.F5;
            case 6 -> Keys.F6;
            case 7 -> Keys.F7;
            case 8 -> Keys.F8;
            case 9 -> Keys.F9;
            case 10 -> Keys.F10;
            case 11 -> Keys.F11;
            case 12 -> Keys.F12;
            default -> throw new IllegalArgumentException("F" + number);
        };
    }

    @Test
    void modifierBitsAreDistinctFlags() {
        int all = Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER;
        assertEquals(0xF, all, "the four modifiers occupy four distinct bits");
    }
}
