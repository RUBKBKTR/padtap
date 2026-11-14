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

import java.util.List;
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

    private Pad(Context c) {
        ctx = c;
        cfg = new Config(c);
        engine = new Engine(touchOut, hooks, cfg.p());
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

    // ---------------- touches / engine ----------------

    private int offX, offY;
    final Engine engine;
    private boolean running;
    private long lastNs;
    private final boolean[] lastVisible = new boolean[2];

    // setup mode
    boolean setup;
    int sel = -1;       // selected button code
    int selStick = -1;  // selected stick (0/1)

    private final Engine.Out touchOut = new Engine.Out() {
        @Override
        public void down(int id, float x, float y) {
            IPadService s = svc;
            if (s == null) return;
            try {
                s.touchDown(id, x + offX, y + offY);
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void move(int id, float x, float y) {
            IPadService s = svc;
            if (s == null) return;
            try {
                s.touchMove(id, x + offX, y + offY);
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void up(int id) {
            IPadService s = svc;
            if (s == null) return;
            try {
                s.touchUp(id);
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void ripple(float x, float y) {
            if (view != null) view.onRipple(x, y);
        }

        @Override
        public void hit(int code) {
            if (view != null) view.onHit(code);
        }
    };

    private final Engine.Hooks hooks = new Engine.Hooks() {
        @Override
        public void onCombo() {
            setSetup(!setup);
        }

        @Override
        public void onSetupPress(int code) {
            if (sel == code) {
                placeAtCursor(code);
                return;
            }
            sel = code;
            selStick = -1;
            if (view != null) view.onSetup();
        }
    };

    void setSurface(int width, int height, int ox, int oy) {
        if (width <= 0 || height <= 0) return;
        offX = ox;
        offY = oy;
        engine.setSize(width, height);
    }

    int w() {
        return engine.w;
    }

    int h() {
        return engine.h;
    }

    void releaseAll() {
        engine.releaseAll();
    }

    /** Call after editing the profile / settings. */
    void changed() {
        engine.prof = cfg.p();
        engine.releaseAll();
        cfg.save();
        if (view != null) view.onSetup();
    }

    private final Choreographer.FrameCallback fc = this::frame;

    void startLoop() {
        if (running) return;
        running = true;
        lastNs = 0;
        engine.prof = cfg.p();
        Choreographer.getInstance().postFrameCallback(fc);
    }

    void stopLoop() {
        running = false;
        setup = false;
        engine.setup = false;
        sel = -1;
        selStick = -1;
        engine.releaseAll();
    }

    private void frame(long ns) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(fc);
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        engine.prof = cfg.p();
        boolean redraw = engine.frame(btns, axes, android.os.SystemClock.uptimeMillis(), dt);
        for (int s = 0; s < 2; s++) {
            boolean v = cursorVisible(s);
            if (v != lastVisible[s]) redraw = true;
            lastVisible[s] = v;
        }
        if (redraw && view != null) view.onPadFrame();
    }

    boolean cursorVisible(int s) {
        if (svc == null) return false;
        if (cfg.p().sticks[s].mode != Model.S_CURSOR) return false;
        if (setup) return true;
        return cfg.showCursor && (engine.idle[s] < 2.5f || engine.anyHeldOnCursor(s));
    }

    // ---------------- setup mode ----------------

    void setSetup(boolean v) {
        engine.releaseAll();
        setup = v;
        engine.setup = v;
        sel = -1;
        selStick = -1;
        engine.idle[0] = engine.idle[1] = 0;
        if (view != null) view.onSetup();
    }

    void selectStick(int s) {
        sel = -1;
        selStick = s;
        if (view != null) view.onSetup();
    }

    void deselect() {
        sel = -1;
        selStick = -1;
        if (view != null) view.onSetup();
    }

    /** Places button {@code code} at overlay pixel (x, y). */
    void place(int code, float x, float y) {
        Model.Btn b = cfg.p().btns.get(code);
        if (b == null) {
            b = new Model.Btn();
            cfg.p().btns.put(code, b);
        }
        b.target = Model.T_POS;
        b.x = x / w();
        b.y = y / h();
        msg(Model.label(code) + " を配置したよ");
        changed();
    }

    private void placeAtCursor(int code) {
        int s = engine.dpadStick();
        if (s < 0) {
            msg("カーソルがないから、画面をタップして置いてね");
            return;
        }
        place(code, engine.cur[s][0], engine.cur[s][1]);
    }

    void setStickCenter(int s, float x, float y) {
        Model.Stick k = cfg.p().sticks[s];
        k.cx = Engine.clamp(x / w(), 0, 1);
        k.cy = Engine.clamp(y / h(), 0, 1);
        changed();
    }

    void clearBtn(int code) {
        cfg.p().btns.remove(code);
        msg(Model.label(code) + " を解除したよ");
        changed();
    }

    void switchProfile(int delta) {
        engine.releaseAll();
        int n = cfg.profiles.size();
        cfg.current = ((cfg.current + delta) % n + n) % n;
        cfg.save();
        engine.prof = cfg.p();
        fireWatchers();
    }

    void msg(String s) {
        if (view != null) view.onMessage(s);
    }
}
