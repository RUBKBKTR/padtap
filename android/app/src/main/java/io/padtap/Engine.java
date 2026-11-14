package io.padtap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controller state → touch logic. Pure Java (no Android), driven once per frame.
 * Coordinates are in overlay pixels (0..w, 0..h).
 */
final class Engine {
    interface Out {
        void down(int id, float x, float y);

        void move(int id, float x, float y);

        void up(int id);

        void ripple(float x, float y);

        void hit(int code);
    }

    interface Hooks {
        void onCombo();

        void onSetupPress(int code);
    }

    static final int MAX_POINTERS = 10;

    private final Out out;
    private final Hooks hooks;
    Model.Profile prof;
    int w = 1080, h = 2400;
    boolean setup;

    /** cursor positions per stick */
    final float[][] cur = new float[2][2];
    final float[] idle = {99f, 99f};
    /** stick finger (JOY / SWIPE) */
    final int[] stickPid = {-1, -1};
    final float[][] touch = new float[2][2];
    /** setup-mode preview of where the stick would touch */
    final float[][] preview = new float[2][2];
    final boolean[] previewOn = new boolean[2];

    private final Map<Integer, Held> held = new HashMap<>();
    private final boolean[] pidUsed = new boolean[MAX_POINTERS];
    private Set<Integer> prev = new HashSet<>();
    private float dpHold;
    private boolean curInit;

    static final class Held {
        int code, pid, target;
        boolean turbo, isDown;
        long downUntil, nextDown;
        int interval;
    }

    Engine(Out out, Hooks hooks, Model.Profile prof) {
        this.out = out;
        this.hooks = hooks;
        this.prof = prof;
    }

    // ---------------- geometry ----------------

    void setSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        w = width;
        h = height;
        if (!curInit) {
            resetCursors();
            curInit = true;
        }
        for (int s = 0; s < 2; s++) {
            cur[s][0] = clamp(cur[s][0], 0, w - 1);
            cur[s][1] = clamp(cur[s][1], 0, h - 1);
        }
    }

    void resetCursors() {
        cur[0][0] = w * 0.4f;
        cur[0][1] = h * 0.5f;
        cur[1][0] = w * 0.6f;
        cur[1][1] = h * 0.5f;
    }

    float shortSide() {
        return Math.min(w, h);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------- pointers ----------------

    private int alloc() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (!pidUsed[i]) {
                pidUsed[i] = true;
                return i;
            }
        }
        return -1;
    }

    private void free(int id) {
        if (id >= 0 && id < MAX_POINTERS) pidUsed[id] = false;
    }

    void releaseAll() {
        for (Held hd : held.values()) {
            if (hd.isDown) out.up(hd.pid);
            free(hd.pid);
        }
        held.clear();
        for (int s = 0; s < 2; s++) liftStick(s);
    }

    private void liftStick(int s) {
        if (stickPid[s] >= 0) {
            out.up(stickPid[s]);
            free(stickPid[s]);
            stickPid[s] = -1;
        }
    }

    boolean anyHeldOnCursor(int s) {
        for (Held hd : held.values()) if (Model.cursorSide(hd.target) == s) return true;
        return false;
    }

    int heldCount() {
        return held.size();
    }

    // ---------------- frame ----------------

    /** @return true if anything visible changed */
    boolean frame(int[] buttons, float[] axes, long now, float dt) {
        Set<Integer> nowSet = new HashSet<>();
        for (int b : buttons) nowSet.add(b);

        boolean combo = hasSelect(nowSet) && hasStart(nowSet);
        boolean prevCombo = hasSelect(prev) && hasStart(prev);
        if (combo && !prevCombo) {
            prev = nowSet;
            releaseAll();
            hooks.onCombo();
            return true;
        }
        boolean changed = false;
        for (int c : nowSet) {
            if (!prev.contains(c)) {
                onPress(c, now);
                changed = true;
            }
        }
        for (int c : prev) {
            if (!nowSet.contains(c)) {
                onRelease(c);
                changed = true;
            }
        }
        prev = nowSet;

        // D-pad moves the first cursor-mode stick's cursor, unless that direction is assigned.
        int ds = setup ? -1 : dpadStick();
        int dx = 0, dy = 0;
        if (ds >= 0) {
            dx = dpad(nowSet, 0x223) - dpad(nowSet, 0x222);
            dy = dpad(nowSet, 0x221) - dpad(nowSet, 0x220);
        }
        dpHold = (dx != 0 || dy != 0) ? dpHold + dt : 0;

        for (int s = 0; s < 2; s++) {
            float x = axes != null && axes.length > 2 * s + 1 ? axes[2 * s] : 0;
            float y = axes != null && axes.length > 2 * s + 1 ? axes[2 * s + 1] : 0;
            if (stick(s, x, y, s == ds ? dx : 0, s == ds ? dy : 0, dt)) changed = true;
        }
        if (turbo(now)) changed = true;
        return changed;
    }

    private int dpad(Set<Integer> s, int code) {
        return s.contains(code) && !prof.btns.containsKey(code) ? 1 : 0;
    }

    int dpadStick() {
        for (int s = 0; s < 2; s++) if (prof.sticks[s].mode == Model.S_CURSOR) return s;
        return -1;
    }

    private static boolean hasSelect(Set<Integer> s) {
        for (int c : s) if (Model.isSelect(c)) return true;
        return false;
    }

    private static boolean hasStart(Set<Integer> s) {
        for (int c : s) if (Model.isStart(c)) return true;
        return false;
    }

    /** radial deadzone, returns magnitude 0..1 */
    static float applyDead(float mag, float dead) {
        if (mag <= dead) return 0;
        return Math.min(1f, (mag - dead) / Math.max(0.01f, 1f - dead));
    }

    private boolean stick(int s, float x, float y, int ddx, int ddy, float dt) {
        Model.Stick k = prof.sticks[s];
        float mag = (float) Math.hypot(x, y);
        float m = applyDead(mag, k.dead);
        float ux = mag > 1e-6f ? x / mag : 0, uy = mag > 1e-6f ? y / mag : 0;
        float S = shortSide();
        boolean wasPreview = previewOn[s];
        previewOn[s] = false;

        if (k.mode != Model.S_JOY && k.mode != Model.S_SWIPE && stickPid[s] >= 0) {
            liftStick(s);
        }
        switch (k.mode) {
            case Model.S_CURSOR: {
                float acc = Math.min(1f, 0.3f + dpHold * 0.9f);
                float vx = ux * m * m + ddx * acc, vy = uy * m * m + ddy * acc;
                if (vx == 0 && vy == 0) {
                    idle[s] += dt;
                    return false;
                }
                float sp = S * 1.2f * k.speed;
                cur[s][0] = clamp(cur[s][0] + vx * sp * dt, 0, w - 1);
                cur[s][1] = clamp(cur[s][1] + vy * sp * dt, 0, h - 1);
                idle[s] = 0;
                for (Held hd : held.values()) {
                    if (Model.cursorSide(hd.target) == s && hd.isDown) out.move(hd.pid, cur[s][0], cur[s][1]);
                }
                return true;
            }
            case Model.S_JOY: {
                float cx = k.cx * w, cy = k.cy * h, R = k.radius * S;
                float tx = cx + ux * m * R, ty = cy + uy * m * R;
                if (setup) {
                    previewOn[s] = m > 0;
                    preview[s][0] = tx;
                    preview[s][1] = ty;
                    return m > 0 || wasPreview;
                }
                if (m > 0) {
                    if (stickPid[s] < 0) {
                        int id = alloc();
                        if (id < 0) return false;
                        stickPid[s] = id;
                        touch[s][0] = cx;
                        touch[s][1] = cy;
                        out.down(id, cx, cy);
                        return true;
                    }
                    if (Math.abs(tx - touch[s][0]) + Math.abs(ty - touch[s][1]) > 0.5f) {
                        touch[s][0] = tx;
                        touch[s][1] = ty;
                        out.move(stickPid[s], tx, ty);
                        return true;
                    }
                    return false;
                } else if (stickPid[s] >= 0) {
                    liftStick(s);
                    return true;
                }
                return false;
            }
            case Model.S_SWIPE: {
                float cx = k.cx * w, cy = k.cy * h, R = k.radius * S;
                if (setup) {
                    previewOn[s] = m > 0;
                    preview[s][0] = cx + ux * m * R;
                    preview[s][1] = cy + uy * m * R;
                    return m > 0 || wasPreview;
                }
                if (m > 0) {
                    if (stickPid[s] < 0) {
                        int id = alloc();
                        if (id < 0) return false;
                        stickPid[s] = id;
                        touch[s][0] = cx;
                        touch[s][1] = cy;
                        out.down(id, cx, cy);
                        return true;
                    }
                    float sp = S * 1.5f * k.speed;
                    float nx = touch[s][0] + ux * m * sp * dt;
                    float ny = touch[s][1] + uy * m * sp * dt;
                    if (!k.unlimited) {
                        float ddx2 = nx - cx, ddy2 = ny - cy;
                        float d = (float) Math.hypot(ddx2, ddy2);
                        if (d > R && d > 0) {
                            nx = cx + ddx2 / d * R;
                            ny = cy + ddy2 / d * R;
                        }
                    }
                    nx = clamp(nx, 0, w - 1);
                    ny = clamp(ny, 0, h - 1);
                    if (Math.abs(nx - touch[s][0]) + Math.abs(ny - touch[s][1]) > 0.01f) {
                        touch[s][0] = nx;
                        touch[s][1] = ny;
                        out.move(stickPid[s], nx, ny);
                        return true;
                    }
                    return false;
                } else if (stickPid[s] >= 0) {
                    liftStick(s);
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    // ---------------- buttons ----------------

    private float[] posFor(int code, int target) {
        int side = Model.cursorSide(target);
        if (side >= 0) return new float[]{cur[side][0], cur[side][1]};
        Model.Btn b = prof.btns.get(code);
        if (b == null) return new float[]{w / 2f, h / 2f};
        return new float[]{b.x * w, b.y * h};
    }

    static long downDuration(int interval) {
        return Math.max(10, Math.min(40, interval / 2));
    }

    private void onPress(int code, long now) {
        if (setup) {
            hooks.onSetupPress(code);
            return;
        }
        Model.Btn b = prof.btns.get(code);
        if (b == null) return;
        int id = alloc();
        if (id < 0) return;
        Held hd = new Held();
        hd.code = code;
        hd.pid = id;
        hd.target = b.target;
        hd.turbo = b.mode == Model.M_TURBO;
        hd.interval = Math.max(30, b.interval);
        float[] p = posFor(code, b.target);
        out.down(id, p[0], p[1]);
        hd.isDown = true;
        if (hd.turbo) {
            hd.downUntil = now + downDuration(hd.interval);
            hd.nextDown = now + hd.interval;
        }
        held.put(code, hd);
        out.ripple(p[0], p[1]);
        int side = Model.cursorSide(b.target);
        if (side >= 0) idle[side] = 0;
        else out.hit(code);
    }

    private void onRelease(int code) {
        Held hd = held.remove(code);
        if (hd == null) return;
        if (hd.isDown) out.up(hd.pid);
        free(hd.pid);
    }

    private boolean turbo(long now) {
        boolean ch = false;
        for (Held hd : held.values()) {
            if (!hd.turbo) continue;
            if (hd.isDown && now >= hd.downUntil) {
                out.up(hd.pid);
                hd.isDown = false;
                ch = true;
            }
            if (!hd.isDown && now >= hd.nextDown) {
                float[] p = posFor(hd.code, hd.target);
                out.down(hd.pid, p[0], p[1]);
                hd.isDown = true;
                hd.downUntil = now + downDuration(hd.interval);
                hd.nextDown += hd.interval;
                if (hd.nextDown <= now) hd.nextDown = now + hd.interval;
                out.ripple(p[0], p[1]);
                if (hd.target == Model.T_POS) out.hit(hd.code);
                ch = true;
            }
        }
        return ch;
    }
}
