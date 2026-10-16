//#if >=26.3
package gg.kite.client;

import gg.kite.core.Keys;

/**
 * 26.3+ reads input through SDL3: key events carry SDL scancodes (USB HID usages) and mouse buttons are 1 = left,
 * 2 = middle, 3 = right. The core speaks GLFW codes (see {@link Keys}) and 0/1/2 buttons, so translate at the edge.
 */
public final class SdlInput {
    private SdlInput() {}

    /** SDL scancode -> GLFW key code, or {@link Keys#NONE} for keys the core has no code for. */
    public static int toGlfw(int sc) {
        if (sc >= 4 && sc <= 29) return 'A' + (sc - 4);
        if (sc >= 30 && sc <= 38) return '1' + (sc - 30);
        if (sc >= 58 && sc <= 69) return 290 + (sc - 58); // F1-F12
        if (sc >= 104 && sc <= 115) return 302 + (sc - 104); // F13-F24
        if (sc >= 89 && sc <= 97) return 321 + (sc - 89); // keypad 1-9
        switch (sc) {
            case 39: return '0';
            case 40: return 257; // enter
            case 41: return 256; // escape
            case 42: return 259; // backspace
            case 43: return 258; // tab
            case 44: return 32; // space
            case 45: return '-';
            case 46: return '=';
            case 47: return '[';
            case 48: return ']';
            case 49: return '\\';
            case 51: return ';';
            case 52: return '\'';
            case 53: return '`';
            case 54: return ',';
            case 55: return '.';
            case 56: return '/';
            case 57: return 280; // caps lock
            case 73: return 260; // insert
            case 74: return 268; // home
            case 75: return 266; // page up
            case 76: return 261; // delete
            case 77: return 269; // end
            case 78: return 267; // page down
            case 79: return 262; // right
            case 80: return 263; // left
            case 81: return 264; // down
            case 82: return 265; // up
            case 98: return 320; // keypad 0
            case 224: return 341; // left ctrl
            case 225: return 340; // left shift
            case 226: return 342; // left alt
            case 227: return 343; // left super
            case 228: return 345; // right ctrl
            case 229: return 344; // right shift
            case 230: return 346; // right alt
            case 231: return 347; // right super
            default: return Keys.NONE;
        }
    }

    /** SDL mouse button -> core button (0 left, 1 right, 2 middle), or -1 for extra buttons. */
    public static int toCoreButton(int sdl) {
        switch (sdl) {
            case 1: return 0;
            case 3: return 1;
            case 2: return 2;
            default: return -1;
        }
    }
}
//#endif
