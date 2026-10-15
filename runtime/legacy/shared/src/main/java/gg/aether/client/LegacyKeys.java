package gg.aether.client;

import org.lwjgl.input.Keyboard;

/** LWJGL 2 key codes (legacy Minecraft) to the GLFW codes core uses ({@link gg.aether.core.Keys}), and back. */
final class LegacyKeys {
    private LegacyKeys() {}

    private static final int[] TO_GLFW = new int[256];
    private static final int[] TO_LWJGL = new int[400];

    private static void map(int lwjgl, int glfw) {
        TO_GLFW[lwjgl] = glfw;
        TO_LWJGL[glfw] = lwjgl;
    }

    static {
        java.util.Arrays.fill(TO_GLFW, -1);
        java.util.Arrays.fill(TO_LWJGL, 0);
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] letterCodes = { Keyboard.KEY_A, Keyboard.KEY_B, Keyboard.KEY_C, Keyboard.KEY_D, Keyboard.KEY_E, Keyboard.KEY_F, Keyboard.KEY_G,
                Keyboard.KEY_H, Keyboard.KEY_I, Keyboard.KEY_J, Keyboard.KEY_K, Keyboard.KEY_L, Keyboard.KEY_M, Keyboard.KEY_N, Keyboard.KEY_O,
                Keyboard.KEY_P, Keyboard.KEY_Q, Keyboard.KEY_R, Keyboard.KEY_S, Keyboard.KEY_T, Keyboard.KEY_U, Keyboard.KEY_V, Keyboard.KEY_W,
                Keyboard.KEY_X, Keyboard.KEY_Y, Keyboard.KEY_Z };
        for (int i = 0; i < letters.length(); i++) map(letterCodes[i], letters.charAt(i));
        int[] digits = { Keyboard.KEY_0, Keyboard.KEY_1, Keyboard.KEY_2, Keyboard.KEY_3, Keyboard.KEY_4, Keyboard.KEY_5, Keyboard.KEY_6,
                Keyboard.KEY_7, Keyboard.KEY_8, Keyboard.KEY_9 };
        for (int i = 0; i < 10; i++) map(digits[i], '0' + i);
        int[] fkeys = { Keyboard.KEY_F1, Keyboard.KEY_F2, Keyboard.KEY_F3, Keyboard.KEY_F4, Keyboard.KEY_F5, Keyboard.KEY_F6, Keyboard.KEY_F7,
                Keyboard.KEY_F8, Keyboard.KEY_F9, Keyboard.KEY_F10, Keyboard.KEY_F11, Keyboard.KEY_F12 };
        for (int i = 0; i < fkeys.length; i++) map(fkeys[i], 290 + i);
        int[] numpad = { Keyboard.KEY_NUMPAD0, Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
                Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9 };
        for (int i = 0; i < numpad.length; i++) map(numpad[i], 320 + i);
        map(Keyboard.KEY_SPACE, 32);
        map(Keyboard.KEY_APOSTROPHE, 39);
        map(Keyboard.KEY_COMMA, 44);
        map(Keyboard.KEY_MINUS, 45);
        map(Keyboard.KEY_PERIOD, 46);
        map(Keyboard.KEY_SLASH, 47);
        map(Keyboard.KEY_SEMICOLON, 59);
        map(Keyboard.KEY_EQUALS, 61);
        map(Keyboard.KEY_LBRACKET, 91);
        map(Keyboard.KEY_BACKSLASH, 92);
        map(Keyboard.KEY_RBRACKET, 93);
        map(Keyboard.KEY_GRAVE, 96);
        map(Keyboard.KEY_ESCAPE, 256);
        map(Keyboard.KEY_RETURN, 257);
        map(Keyboard.KEY_TAB, 258);
        map(Keyboard.KEY_BACK, 259);
        map(Keyboard.KEY_INSERT, 260);
        map(Keyboard.KEY_DELETE, 261);
        map(Keyboard.KEY_RIGHT, 262);
        map(Keyboard.KEY_LEFT, 263);
        map(Keyboard.KEY_DOWN, 264);
        map(Keyboard.KEY_UP, 265);
        map(Keyboard.KEY_PRIOR, 266);
        map(Keyboard.KEY_NEXT, 267);
        map(Keyboard.KEY_HOME, 268);
        map(Keyboard.KEY_END, 269);
        map(Keyboard.KEY_CAPITAL, 280);
        map(Keyboard.KEY_NUMPADENTER, 335);
        map(Keyboard.KEY_LSHIFT, 340);
        map(Keyboard.KEY_LCONTROL, 341);
        map(Keyboard.KEY_LMENU, 342);
        map(Keyboard.KEY_LMETA, 343);
        map(Keyboard.KEY_RSHIFT, 344);
        map(Keyboard.KEY_RCONTROL, 345);
        map(Keyboard.KEY_RMENU, 346);
        map(Keyboard.KEY_RMETA, 347);
    }

    /** GLFW code for an LWJGL 2 key, -1 if it has none. */
    static int toGlfw(int lwjgl) { return lwjgl >= 0 && lwjgl < TO_GLFW.length ? TO_GLFW[lwjgl] : -1; }

    /** LWJGL 2 code for a GLFW key, 0 (KEY_NONE) if it has none. */
    static int toLwjgl(int glfw) { return glfw >= 0 && glfw < TO_LWJGL.length ? TO_LWJGL[glfw] : 0; }

    /** Is the GLFW key {@code glfw} held right now? */
    static boolean isDown(int glfw) {
        int k = toLwjgl(glfw);
        return k > 0 && Keyboard.isKeyDown(k);
    }
}
