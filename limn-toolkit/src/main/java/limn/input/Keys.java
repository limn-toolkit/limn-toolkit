package limn.input;

/**
 * Toolkit key codes and modifier bits. Values are numerically compatible with
 * GLFW so the LWJGL backend maps for free, but these constants are the public
 * contract; application code never touches backend types.
 */
public final class Keys {

    private Keys() {
    }

    // Modifier bitmask (KeyEvent.modifiers / MouseEvent.modifiers)
    public static final int MOD_SHIFT = 0x1;
    public static final int MOD_CONTROL = 0x2;
    public static final int MOD_ALT = 0x4;
    public static final int MOD_SUPER = 0x8;

    // Printable. Letters and digits ARE their ASCII values, so `Keys.F` and
    // `'F'` are interchangeable; they are spelled out because an app binding a
    // shortcut should not have to know that.
    public static final int SPACE = 32;
    public static final int COMMA = 44;
    public static final int MINUS = 45;
    public static final int PERIOD = 46;
    public static final int SLASH = 47;
    public static final int NUM_0 = 48; // '0'..'9' are 48..57
    public static final int NUM_1 = 49;
    public static final int NUM_2 = 50;
    public static final int NUM_3 = 51;
    public static final int NUM_4 = 52;
    public static final int NUM_5 = 53;
    public static final int NUM_6 = 54;
    public static final int NUM_7 = 55;
    public static final int NUM_8 = 56;
    public static final int NUM_9 = 57;
    public static final int EQUAL = 61;
    public static final int A = 65;    // 'A'..'Z' are 65..90
    public static final int B = 66;
    public static final int C = 67;
    public static final int D = 68;
    public static final int E = 69;
    public static final int F = 70;
    public static final int G = 71;
    public static final int H = 72;
    public static final int I = 73;
    public static final int J = 74;
    public static final int K = 75;
    public static final int L = 76;
    public static final int M = 77;
    public static final int N = 78;
    public static final int O = 79;
    public static final int P = 80;
    public static final int Q = 81;
    public static final int R = 82;
    public static final int S = 83;
    public static final int T = 84;
    public static final int U = 85;
    public static final int V = 86;
    public static final int W = 87;
    public static final int X = 88;
    public static final int Y = 89;
    public static final int Z = 90;
    public static final int LEFT_BRACKET = 91;
    public static final int BACKSLASH = 92;
    public static final int RIGHT_BRACKET = 93;

    // Control
    public static final int ESCAPE = 256;
    public static final int ENTER = 257;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int INSERT = 260;
    public static final int DELETE = 261;
    public static final int RIGHT = 262;
    public static final int LEFT = 263;
    public static final int DOWN = 264;
    public static final int UP = 265;
    public static final int PAGE_UP = 266;
    public static final int PAGE_DOWN = 267;
    public static final int HOME = 268;
    public static final int END = 269;

    // Function keys (F1..F12 are 290..301)
    public static final int F1 = 290;
    public static final int F2 = 291;
    public static final int F3 = 292;
    public static final int F4 = 293;
    public static final int F5 = 294;
    public static final int F6 = 295;
    public static final int F7 = 296;
    public static final int F8 = 297;
    public static final int F9 = 298;
    public static final int F10 = 299;
    public static final int F11 = 300;
    public static final int F12 = 301;

    // Modifier keys themselves
    public static final int LEFT_SHIFT = 340;
    public static final int LEFT_CONTROL = 341;
    public static final int LEFT_ALT = 342;
    public static final int LEFT_SUPER = 343;
    public static final int RIGHT_SHIFT = 344;
    public static final int RIGHT_CONTROL = 345;
    public static final int RIGHT_ALT = 346;
    public static final int RIGHT_SUPER = 347;

    /**
     * The context-menu key, between the right Alt and Control on a full keyboard. Absent from
     * most compact and Apple layouts, where Shift+F10 is the same request. Bind both, or a
     * keyboard user on half the machines in the world has no route to the menu.
     */
    public static final int MENU = 348;

    // Mouse buttons (MouseEvent.button)
    public static final int MOUSE_LEFT = 0;
    public static final int MOUSE_RIGHT = 1;
    public static final int MOUSE_MIDDLE = 2;
}
