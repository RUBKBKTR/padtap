package io.padtap;

import java.util.LinkedHashMap;

/** Plain data model for button / stick mappings (no Android dependencies). */
final class Model {
    private Model() {}

    // button target
    static final int T_POS = 0, T_CUR_L = 1, T_CUR_R = 2;
    // button press mode
    static final int M_HOLD = 0, M_TURBO = 1;
    // stick mode
    static final int S_OFF = 0, S_CURSOR = 1, S_JOY = 2, S_SWIPE = 3;

    static final int BTN_A = 0x130;

    static final class Btn {
        int target = T_POS;
        float x = 0.5f, y = 0.5f;   // normalized, for T_POS
        int mode = M_HOLD;
        int interval = 100;         // ms, for M_TURBO
        float size = 48;            // dp diameter of the marker / touch

        Btn() {}

        Btn(int target) {
            this.target = target;
        }
    }

    static final class Stick {
        int mode;
        float cx, cy;               // normalized center (JOY / SWIPE)
        float radius = 0.12f;       // fraction of the screen's short side
        boolean unlimited = false;  // SWIPE only
        float dead = 0.15f;         // 0..0.5
        float speed = 1f;           // CURSOR / SWIPE

        Stick(int mode, float cx, float cy) {
            this.mode = mode;
            this.cx = cx;
            this.cy = cy;
        }
    }

    static final class Profile {
        String name;
        final LinkedHashMap<Integer, Btn> btns = new LinkedHashMap<>();
        final Stick[] sticks = {new Stick(S_CURSOR, 0.25f, 0.7f), new Stick(S_OFF, 0.75f, 0.7f)};

        Profile(String name) {
            this.name = name;
        }

        static Profile defaults(String name) {
            Profile p = new Profile(name);
            p.btns.put(BTN_A, new Btn(T_CUR_L));
            return p;
        }
    }

    static int cursorSide(int target) {
        return target == T_CUR_L ? 0 : target == T_CUR_R ? 1 : -1;
    }

    static boolean isDpad(int c) {
        return c >= 0x220 && c <= 0x223;
    }

    static boolean isSelect(int c) {
        return c == 0x13a || c == 158; // BTN_SELECT / KEY_BACK
    }

    static boolean isStart(int c) {
        return c == 0x13b || c == 139; // BTN_START / KEY_MENU
    }

    static String label(int c) {
        switch (c) {
            case 0x130: return "A";
            case 0x131: return "B";
            case 0x132: return "C";
            case 0x133: return "X";
            case 0x134: return "Y";
            case 0x135: return "Z";
            case 0x136: return "LB";
            case 0x137: return "RB";
            case 0x138: return "LT";
            case 0x139: return "RT";
            case 0x13a: case 158: return "SELECT";
            case 0x13b: case 139: return "START";
            case 0x13c: case 172: return "HOME";
            case 0x13d: return "L3";
            case 0x13e: return "R3";
            case 0x220: return "↑";
            case 0x221: return "↓";
            case 0x222: return "←";
            case 0x223: return "→";
            default:
                if (c >= 0x120 && c < 0x130) return "B" + (c - 0x120 + 1);
                if (c >= 0x2c0 && c < 0x300) return "T" + (c - 0x2c0 + 1);
                return "#" + Integer.toHexString(c);
        }
    }

    static String stickName(int s) {
        return s == 0 ? "左スティック" : "右スティック";
    }
}
