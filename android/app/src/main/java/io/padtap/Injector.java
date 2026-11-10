package io.padtap;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Injects real multi-touch events through InputManager (needs shell/root, i.e. the Shizuku process). */
final class Injector {
    private static final String TAG = "PadTapInj";
    private Object im;
    private Method inject;
    private String status = "ok";
    private final LinkedHashMap<Integer, float[]> pointers = new LinkedHashMap<>();
    private long downTime;

    Injector() {
        try {
            Class<?> c;
            try {
                c = Class.forName("android.hardware.input.InputManagerGlobal");
            } catch (ClassNotFoundException e) {
                c = Class.forName("android.hardware.input.InputManager");
            }
            Method gi = c.getDeclaredMethod("getInstance");
            gi.setAccessible(true);
            im = gi.invoke(null);
            inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
        } catch (Throwable t) {
            status = "init failed: " + t;
            Log.e(TAG, "init", t);
        }
    }

    String status() {
        return status;
    }

    private int indexOf(int id) {
        int i = 0;
        for (Integer k : pointers.keySet()) {
            if (k == id) return i;
            i++;
        }
        return 0;
    }

    private void send(int action) {
        if (im == null || inject == null) return;
        int n = pointers.size();
        if (n == 0) return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        int i = 0;
        for (Map.Entry<Integer, float[]> e : pointers.entrySet()) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = e.getKey();
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = e.getValue()[0];
            c.y = e.getValue()[1];
            c.pressure = 1f;
            c.size = 0.05f;
            c.touchMajor = 12f;
            c.touchMinor = 12f;
            props[i] = p;
            coords[i] = c;
            i++;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, n, props, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inject.invoke(im, ev, 0);
        } catch (Throwable t) {
            status = "inject failed: " + t;
            Log.e(TAG, "inject", t);
        } finally {
            ev.recycle();
        }
    }

    synchronized void down(int id, float x, float y) {
        if (pointers.containsKey(id)) up(id);
        pointers.put(id, new float[]{x, y});
        if (pointers.size() == 1) {
            downTime = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN);
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN | (indexOf(id) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        }
    }

    synchronized void move(int id, float x, float y) {
        float[] p = pointers.get(id);
        if (p == null) return;
        p[0] = x;
        p[1] = y;
        send(MotionEvent.ACTION_MOVE);
    }

    synchronized void up(int id) {
        if (!pointers.containsKey(id)) return;
        if (pointers.size() == 1) {
            send(MotionEvent.ACTION_UP);
        } else {
            send(MotionEvent.ACTION_POINTER_UP | (indexOf(id) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        }
        pointers.remove(id);
    }

    synchronized void releaseAll() {
        for (Integer id : new ArrayList<>(pointers.keySet())) up(id);
    }
}
