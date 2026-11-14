package io.padtap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class EngineTest {
    static final int A = 0x130, B = 0x131, X = 0x133, RB = 0x137, SEL = 0x13a, START = 0x13b;
    static final int UP = 0x220, RIGHT = 0x223;

    static final class Rec implements Engine.Out, Engine.Hooks {
        final List<String> ev = new ArrayList<>();
        int combos;
        final List<Integer> setupPresses = new ArrayList<>();

        public void down(int id, float x, float y) {
            ev.add("D" + id + "@" + Math.round(x) + "," + Math.round(y));
        }

        public void move(int id, float x, float y) {
            ev.add("M" + id + "@" + Math.round(x) + "," + Math.round(y));
        }

        public void up(int id) {
            ev.add("U" + id);
        }

        public void ripple(float x, float y) {
        }

        public void hit(int code) {
        }

        public void onCombo() {
            combos++;
        }

        public void onSetupPress(int code) {
            setupPresses.add(code);
        }

        long count(String prefix) {
            return ev.stream().filter(s -> s.startsWith(prefix)).count();
        }

        String last() {
            return ev.isEmpty() ? "" : ev.get(ev.size() - 1);
        }
    }

    Rec rec;
    Engine e;
    long t;

    Engine make(Model.Profile p) {
        rec = new Rec();
        e = new Engine(rec, rec, p);
        e.setSize(1000, 2000);
        t = 1000;
        return e;
    }

    void step(int[] b, float[] ax) {
        e.frame(b, ax, t, 0.016f);
        t += 16;
    }

    void steps(int n, int[] b, float[] ax) {
        for (int i = 0; i < n; i++) step(b, ax);
    }

    static final float[] Z = {0, 0, 0, 0};

    @Test
    public void cursorTapAndDrag() {
        make(Model.Profile.defaults("t"));
        step(new int[]{A}, Z);
        assertEquals("D0@400,1000", rec.ev.get(0));
        steps(10, new int[]{A}, new float[]{1, 0, 0, 0});
        assertTrue(rec.count("M0") > 5);
        step(new int[0], Z);
        assertEquals("U0", rec.last());
        assertTrue(e.cur[0][0] > 500);
    }

    @Test
    public void twoCursorsSeparateButtons() {
        Model.Profile p = Model.Profile.defaults("t");
        p.sticks[1].mode = Model.S_CURSOR;
        p.btns.put(RB, new Model.Btn(Model.T_CUR_R));
        make(p);
        steps(10, new int[0], new float[]{0, 0, 0, -1}); // right stick up moves right cursor only
        assertEquals(1000f, e.cur[0][1], 0.01f);
        assertTrue(e.cur[1][1] < 1000);
        step(new int[]{RB}, Z);
        assertEquals("D0@600," + Math.round(e.cur[1][1]), rec.last());
        step(new int[]{RB, A}, Z);
        assertEquals("D1@400,1000", rec.last());
    }

    @Test
    public void positionButtonHold() {
        Model.Profile p = new Model.Profile("t");
        Model.Btn b = new Model.Btn();
        b.x = 0.1f;
        b.y = 0.2f;
        p.btns.put(X, b);
        make(p);
        step(new int[]{X}, Z);
        steps(30, new int[]{X}, Z);
        assertEquals(1, rec.count("D"));
        assertEquals(0, rec.count("U"));
        step(new int[0], Z);
        assertEquals(1, rec.count("U"));
        assertEquals("D0@100,400", rec.ev.get(0));
    }

    @Test
    public void turboRepeats() {
        Model.Profile p = new Model.Profile("t");
        Model.Btn b = new Model.Btn();
        b.mode = Model.M_TURBO;
        b.interval = 100;
        p.btns.put(X, b);
        make(p);
        steps(63, new int[]{X}, Z); // ~1s
        long downs = rec.count("D");
        assertTrue("downs=" + downs, downs >= 9 && downs <= 11);
        step(new int[0], Z);
        assertEquals(rec.count("D"), rec.count("U"));
    }

    @Test
    public void joystickMapsTiltToCircle() {
        Model.Profile p = new Model.Profile("t");
        p.sticks[0].mode = Model.S_JOY;
        p.sticks[0].cx = 0.5f;
        p.sticks[0].cy = 0.5f;
        p.sticks[0].radius = 0.1f; // 100px of short side 1000
        p.sticks[0].dead = 0f;
        make(p);
        step(new int[0], new float[]{1, 0, 0, 0});
        assertEquals("D0@500,1000", rec.last());
        step(new int[0], new float[]{1, 0, 0, 0});
        assertEquals("M0@600,1000", rec.last());
        step(new int[0], new float[]{0, 0.5f, 0, 0});
        assertEquals("M0@500,1050", rec.last());
        step(new int[0], Z);
        assertEquals("U0", rec.last());
    }

    @Test
    public void deadzoneIgnoresSmallTilt() {
        Model.Profile p = new Model.Profile("t");
        p.sticks[0].mode = Model.S_JOY;
        p.sticks[0].dead = 0.2f;
        make(p);
        steps(5, new int[0], new float[]{0.15f, 0.1f, 0, 0});
        assertEquals(0, rec.ev.size());
        step(new int[0], new float[]{0.5f, 0, 0, 0});
        assertEquals(1, rec.count("D"));
        assertEquals(0f, Engine.applyDead(0.2f, 0.2f), 1e-6);
        assertEquals(1f, Engine.applyDead(1f, 0.2f), 1e-6);
    }

    @Test
    public void swipeLimitedAndReturns() {
        Model.Profile p = new Model.Profile("t");
        Model.Stick k = p.sticks[1];
        k.mode = Model.S_SWIPE;
        k.cx = 0.5f;
        k.cy = 0.5f;
        k.radius = 0.1f;
        k.dead = 0;
        make(p);
        steps(60, new int[0], new float[]{0, 0, 1, 0});
        // clamped at radius 100 from center (500,1000)
        assertEquals("M0@600,1000", rec.last());
        step(new int[0], Z);
        assertEquals("U0", rec.last());
        step(new int[0], new float[]{0, 0, 1, 0});
        assertEquals("D0@500,1000", rec.last()); // starts again from center
    }

    @Test
    public void swipeUnlimited() {
        Model.Profile p = new Model.Profile("t");
        Model.Stick k = p.sticks[1];
        k.mode = Model.S_SWIPE;
        k.cx = 0.5f;
        k.cy = 0.5f;
        k.unlimited = true;
        k.dead = 0;
        make(p);
        steps(60, new int[0], new float[]{0, 0, 1, 0});
        assertEquals("M0@999,1000", rec.last()); // stopped only by the screen edge
    }

    @Test
    public void dpadMovesCursorUnlessAssigned() {
        Model.Profile p = Model.Profile.defaults("t");
        make(p);
        steps(10, new int[]{RIGHT}, Z);
        assertTrue(e.cur[0][0] > 400);
        float y = e.cur[0][1];
        Model.Btn b = new Model.Btn();
        p.btns.put(UP, b);
        steps(10, new int[]{UP}, Z);
        assertEquals(y, e.cur[0][1], 0.01f);
        assertEquals(1, rec.count("D"));
    }

    @Test
    public void comboAndSetupRouting() {
        make(Model.Profile.defaults("t"));
        step(new int[]{SEL, START}, Z);
        assertEquals(1, rec.combos);
        e.setup = true;
        step(new int[0], Z);
        step(new int[]{X}, Z);
        step(new int[]{A}, Z);
        assertEquals(2, rec.setupPresses.size());
        assertEquals(0, rec.count("D"));
    }

    @Test
    public void joystickAndButtonsGetDistinctPointers() {
        Model.Profile p = Model.Profile.defaults("t");
        p.sticks[1].mode = Model.S_JOY;
        p.sticks[1].dead = 0;
        make(p);
        step(new int[]{A}, new float[]{0, 0, 1, 0});
        assertTrue(rec.ev.contains("D0@400,1000"));
        assertTrue(rec.ev.stream().anyMatch(s -> s.startsWith("D1@")));
        e.releaseAll();
        assertEquals(2, rec.count("U"));
    }
}
