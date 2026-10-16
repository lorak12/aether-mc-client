package gg.kite.core;

/** GLFW key codes for default keybinds (adapters translate their native codes to these). */
public final class Keys {
    private Keys() {}

    public static final int NONE = -1;
    public static final int B = 66, C = 67, E = 69, G = 71, H = 72, J = 74, K = 75, M = 77, N = 78, V = 86, Z = 90;
    public static final int F1 = 290, F8 = 297, F9 = 298, F10 = 299;
    public static final int LEFT = 263, RIGHT = 262, UP = 265, DOWN = 264;
    public static final int RIGHT_SHIFT = 344, LEFT_CONTROL = 341, RIGHT_CONTROL = 345, LEFT_SUPER = 343, RIGHT_SUPER = 347;
    public static final int A = 65, X = 88, TAB = 258, KP_ENTER = 335, LEFT_SHIFT = 340, HOME = 268, END = 269;
    public static final int ESCAPE = 256, ENTER = 257, BACKSPACE = 259, DELETE = 261;
    public static final int SPACE = 32, SLASH = 47, D = 68, S = 83, T = 84, W = 87;

    /** Short display name for a GLFW key code ("C", "F8", "RShift", "None"). */
    public static String name(int code) {
        if (code == NONE) return "None";
        if ((code >= 65 && code <= 90) || (code >= 48 && code <= 57)) return String.valueOf((char) code);
        if (code >= 290 && code <= 314) return "F" + (code - 289);
        if (code >= 320 && code <= 329) return "Num" + (code - 320);
        switch (code) {
            case 32: return "Space";
            case 39: return "'";
            case 44: return ",";
            case 45: return "-";
            case 46: return ".";
            case 47: return "/";
            case 59: return ";";
            case 61: return "=";
            case 91: return "[";
            case 92: return "\\";
            case 93: return "]";
            case 96: return "`";
            case 256: return "Esc";
            case 257: return "Enter";
            case 258: return "Tab";
            case 259: return "Backspace";
            case 260: return "Insert";
            case 261: return "Delete";
            case 262: return "Right";
            case 263: return "Left";
            case 264: return "Down";
            case 265: return "Up";
            case 266: return "PgUp";
            case 267: return "PgDn";
            case 268: return "Home";
            case 269: return "End";
            case 280: return "CapsLock";
            case 340: return "LShift";
            case 341: return "LCtrl";
            case 342: return "LAlt";
            case 344: return "RShift";
            case 345: return "RCtrl";
            case 346: return "RAlt";
            default: return "Key " + code;
        }
    }
}
