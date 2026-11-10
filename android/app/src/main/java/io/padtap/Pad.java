package io.padtap;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Choreographer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/** App-side core: Shizuku connection, controller state, cursor and button → touch logic. Main thread only. */
final class Pad {
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static Pad inst;

    static synchronized Pad get(Context c) {
        if (inst == null) inst = new Pad(c.getApplicationContext());
        return inst;
    }

    /** Implemented by the overlay. */
    interface View {
        void onPadState();

        void onPadFrame();

        void onRipple(float x, float y);

        void onHit(int code);

        void onSetup();

        void onMessage(String s);
    }

    final Context ctx;
    final Config cfg;
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Runnable> watchers = new CopyOnWriteArrayList<>();
    View view;

    volatile IPadService svc;
    boolean binding;
    String devices = "";
    String lastError = "";

    private volatile int[] btns = new int[0];
    private volatile float[] axes = new float[4];

    // surface (overlay layer) geometry
    int w = 1080, h = 2400;
    private int offX, offY;
    float curX = -1, curY = -1;

    // loop state
    private boolean running;
    private long lastNs;
    private Set<Integer> prev = new HashSet<>();
    private float dpHold;
    float idle = 99;
    private final Map<Integer, Integer> active = new HashMap<>(); // button -> pointer id
    final Set<Integer> cursorHeld = new HashSet<>(); // pointer ids following the cursor
    private boolean lastVisible;

    // setup mode
    boolean setup;
    int sel = -1;

    private Pad(Context c) {
        ctx = c;
        cfg = new Config(c);
        try {
            Shizuku.addBinderReceivedListenerSticky(this::fireWatchers);
            Shizuku.addBinderDeadListener(() -> main.post(() -> {
                svc = null;
                binding = false;
                fireWatchers();
                if (view != null) view.onPadState();
            }));
            Shizuku.addRequestPermissionResultListener((code, res) -> fireWatchers());
        } catch (Throwable ignored) {
        }
    }

    void fireWatchers() {
        main.post(() -> {
            for (Runnable r : watchers) r.run();
        });
    }

    // ---------------- Shizuku ----------------

    boolean shizukuInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    boolean shizukuOld() {
        try {
            return shizukuRunning() && Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    boolean shizukuGranted() {
        try {
            return shizukuRunning() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    void requestShizuku() {
        try {
            Shizuku.requestPermission(1);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            fireWatchers();
        }
    }

    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, PadService.class.getName()))
            .daemon(false)
            .processNameSuffix("svc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private final IPadListener.Stub listener = new IPadListener.Stub() {
        @Override
        public void onState(int[] b, float[] a) {
            btns = b;
            axes = a;
        }

        @Override
        public void onDevices(String d) {
            main.post(() -> {
                devices = d;
                fireWatchers();
                if (view != null) view.onPadState();
            });
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            main.post(() -> {
                binding = false;
                if (b == null || !b.pingBinder()) {
                    lastError = "サービスに接続できなかった";
                    fireWatchers();
                    return;
                }
                IPadService s = IPadService.Stub.asInterface(b);
                try {
                    s.setListener(listener);
                    s.setGrab(cfg.grab);
                    String inf = s.info();
                    lastError = inf.contains("err=") || !inf.contains("inj=ok") ? inf : "";
                } catch (RemoteException e) {
                    lastError = e.toString();
                }
                svc = s;
                fireWatchers();
                if (view != null) view.onPadState();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            main.post(() -> {
                svc = null;
                binding = false;
                fireWatchers();
                if (view != null) view.onPadState();
            });
        }
    };

    void bind() {
        if (svc != null || binding) return;
        if (!shizukuGranted()) {
            lastError = "Shizukuが使えない状態だよ";
            fireWatchers();
            return;
        }
        binding = true;
        try {
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            binding = false;
            lastError = String.valueOf(t);
        }
        fireWatchers();
    }

    void unbind() {
        IPadService s = svc;
        svc = null;
        if (s != null) {
            try {
                s.releaseAll();
                s.setListener(null);
            } catch (Throwable ignored) {
            }
        }
        try {
            Shizuku.unbindUserService(args, conn, true);
        } catch (Throwable ignored) {
        }
        binding = false;
        devices = "";
        btns = new int[0];
        axes = new float[4];
        fireWatchers();
    }

    void setGrab(boolean g) {
        cfg.grab = g;
        cfg.save();
        IPadService s = svc;
        if (s != null) {
            try {
                s.setGrab(g);
            } catch (RemoteException ignored) {
            }
        }
    }

    // ---------------- touches ----------------

    void setSurface(int width, int height, int ox, int oy) {
        if (width <= 0 || height <= 0) return;
        w = width;
        h = height;
        offX = ox;
        offY = oy;
        if (curX < 0) {
            curX = w / 2f;
            curY = h / 2f;
        }
        curX = Math.min(curX, w - 1);
        curY = Math.min(curY, h - 1);
    }

    private void tDown(int id, float x, float y) {
        IPadService s = svc;
        if (s == null) return;
        try {
            s.touchDown(id, x + offX, y + offY);
        } catch (RemoteException ignored) {
        }
    }

    private void tMove(int id, float x, float y) {
        IPadService s = svc;
        if (s == null) return;
        try {
            s.touchMove(id, x + offX, y + offY);
        } catch (RemoteException ignored) {
        }
    }

    private void tUp(int id) {
        IPadService s = svc;
        if (s == null) return;
        try {
            s.touchUp(id);
        } catch (RemoteException ignored) {
        }
    }

    void releaseAll() {
        for (int pid : active.values()) tUp(pid);
        active.clear();
        cursorHeld.clear();
    }

    private int allocPointer() {
        for (int i = 0; i < 10; i++) if (!active.containsValue(i)) return i;
        return 9;
    }

    // ---------------- loop ----------------

    private final Choreographer.FrameCallback fc = this::frame;

    void startLoop() {
        if (running) return;
        running = true;
        lastNs = 0;
        prev = new HashSet<>();
        Choreographer.getInstance().postFrameCallback(fc);
    }

    void stopLoop() {
        running = false;
        setup = false;
        sel = -1;
        releaseAll();
    }

    private static float dz(float v) {
        float a = Math.abs(v);
        if (a < 0.18f) return 0;
        float t = Math.min(1f, (a - 0.18f) / 0.8f);
        return Math.signum(v) * t * t;
    }

    private void frame(long ns) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(fc);
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        int[] b = btns;
        float[] a = axes;
        Set<Integer> now = new HashSet<>();
        for (int x : b) now.add(x);

        boolean combo = hasSelect(now) && hasStart(now);
        boolean prevCombo = hasSelect(prev) && hasStart(prev);
        if (combo && !prevCombo) {
            prev = now;
            setSetup(!setup);
            return;
        }
        for (int x : now) if (!prev.contains(x)) onBtn(x, true);
        for (int x : prev) if (!now.contains(x)) onBtn(x, false);
        prev = now;

        int dx = (now.contains(0x223) ? 1 : 0) - (now.contains(0x222) ? 1 : 0);
        int dy = (now.contains(0x221) ? 1 : 0) - (now.contains(0x220) ? 1 : 0);
        dpHold = (dx != 0 || dy != 0) ? dpHold + dt : 0;
        float acc = Math.min(1f, 0.3f + dpHold * 0.9f);
        float vx = dz(a[0]) + dz(a[2]) * 0.3f + dx * acc;
        float vy = dz(a[1]) + dz(a[3]) * 0.3f + dy * acc;
        boolean redraw = false;
        if (vx != 0 || vy != 0) {
            float sp = Math.min(w, h) * 1.2f * cfg.speed;
            curX = Math.max(0, Math.min(w - 1, curX + vx * sp * dt));
            curY = Math.max(0, Math.min(h - 1, curY + vy * sp * dt));
            for (int pid : cursorHeld) tMove(pid, curX, curY);
            idle = 0;
            redraw = true;
        } else {
            idle += dt;
        }
        boolean vis = cursorVisible();
        if (vis != lastVisible) redraw = true;
        lastVisible = vis;
        if (redraw && view != null) view.onPadFrame();
    }

    private static boolean hasSelect(Set<Integer> s) {
        for (int c : s) if (Config.isSelect(c)) return true;
        return false;
    }

    private static boolean hasStart(Set<Integer> s) {
        for (int c : s) if (Config.isStart(c)) return true;
        return false;
    }

    boolean cursorVisible() {
        if (svc == null) return false;
        if (setup) return true;
        return cfg.showCursor && (idle < 2.5f || !cursorHeld.isEmpty());
    }

    private void onBtn(int code, boolean pressed) {
        if (setup) {
            if (pressed) setupPress(code);
            return;
        }
        if (Config.isDpad(code)) return;
        Config.Profile p = cfg.p();
        if (pressed) {
            if (p.cursor.contains(code)) {
                int pid = allocPointer();
                active.put(code, pid);
                cursorHeld.add(pid);
                tDown(pid, curX, curY);
                idle = 0;
                if (view != null) {
                    view.onRipple(curX, curY);
                    view.onPadFrame();
                }
            } else {
                float[] pos = p.pos.get(code);
                if (pos == null) return;
                int pid = allocPointer();
                active.put(code, pid);
                float x = pos[0] * w, y = pos[1] * h;
                tDown(pid, x, y);
                if (view != null) {
                    view.onRipple(x, y);
                    view.onHit(code);
                }
            }
        } else {
            Integer pid = active.remove(code);
            if (pid != null) {
                boolean wasCursor = cursorHeld.remove(pid);
                tUp(pid);
                if (wasCursor && view != null) view.onPadFrame();
            }
        }
    }

    // ---------------- setup mode ----------------

    void setSetup(boolean v) {
        releaseAll();
        setup = v;
        sel = -1;
        idle = 0;
        if (view != null) view.onSetup();
    }

    private void setupPress(int code) {
        if (Config.isDpad(code)) return;
        if (sel == code) {
            place(code, curX, curY);
            return;
        }
        sel = code;
        if (view != null) view.onSetup();
    }

    void place(int code, float x, float y) {
        cfg.p().setPos(code, x / w, y / h);
        cfg.save();
        sel = -1;
        msg(Config.label(code) + " を配置したよ");
        if (view != null) view.onSetup();
    }

    void setCursorBtn(int code) {
        cfg.p().setCursor(code);
        cfg.save();
        msg(Config.label(code) + " はカーソル位置を押すよ");
        sel = -1;
        if (view != null) view.onSetup();
    }

    void clearBtn(int code) {
        cfg.p().clear(code);
        cfg.save();
        msg(Config.label(code) + " を解除したよ");
        sel = -1;
        if (view != null) view.onSetup();
    }

    void switchProfile(int delta) {
        releaseAll();
        int n = cfg.profiles.size();
        cfg.current = ((cfg.current + delta) % n + n) % n;
        cfg.save();
        fireWatchers();
    }

    void msg(String s) {
        if (view != null) view.onMessage(s);
    }
}
