package io.padtap;

import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs inside the Shizuku user-service process (shell or root uid).
 * Reads gamepads straight from /dev/input (optionally grabbing them so Android and the browser
 * don't see them) and injects touches on request.
 */
public class PadService extends IPadService.Stub {
    private static final String TAG = "PadTapSvc";

    // Linux input constants
    static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    static final int ABS_X = 0, ABS_Y = 1, ABS_Z = 2, ABS_RX = 3, ABS_RY = 4, ABS_RZ = 5,
            ABS_GAS = 9, ABS_BRAKE = 10, ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;

    private final Object lock = new Object();
    private final Object pubLock = new Object();
    private final Map<String, Dev> devs = new HashMap<>();
    private final Set<String> ignored = new HashSet<>();
    private volatile IPadListener listener;
    private volatile boolean wantGrab = true;
    private volatile boolean alive = true;
    private volatile String err = "";
    private Injector inj;
    private int[] lastBtns = new int[0];
    private float[] lastAx = new float[4];

    public PadService() {
        this(null);
    }

    public PadService(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
            }
        } catch (Throwable ignored) {
        }
        inj = new Injector();
        boolean ok = false;
        try {
            System.loadLibrary("padtap");
            ok = true;
        } catch (Throwable t) {
            err = "loadLibrary: " + t;
        }
        if (!ok && ctx != null) {
            try {
                System.load(ctx.getApplicationInfo().nativeLibraryDir + "/libpadtap.so");
                ok = true;
                err = "";
            } catch (Throwable t) {
                err += " / load: " + t;
            }
        }
        if (ok) {
            Thread t = new Thread(this::scanLoop, "padtap-scan");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------------- AIDL ----------------

    @Override
    public void destroy() {
        alive = false;
        synchronized (lock) {
            for (Dev d : devs.values()) {
                d.run = false;
                d.setGrab(false);
            }
        }
        inj.releaseAll();
        System.exit(0);
    }

    @Override
    public void setListener(IPadListener l) {
        listener = l;
        if (l != null) {
            try {
                l.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (listener == l) {
                            listener = null;
                            applyGrab();
                            inj.releaseAll();
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                listener = null;
            }
        } else {
            inj.releaseAll();
        }
        applyGrab();
        sendDevices();
        synchronized (pubLock) {
            lastBtns = new int[]{-1};
        }
        publish();
    }

    @Override
    public void setGrab(boolean on) {
        wantGrab = on;
        applyGrab();
    }

    @Override
    public void touchDown(int id, float x, float y) {
        inj.down(id, x, y);
    }

    @Override
    public void touchMove(int id, float x, float y) {
        inj.move(id, x, y);
    }

    @Override
    public void touchUp(int id) {
        inj.up(id);
    }

    @Override
    public void releaseAll() {
        inj.releaseAll();
    }

    @Override
    public String info() {
        String s = "uid=" + android.os.Process.myUid() + " inj=" + inj.status();
        if (!err.isEmpty()) s += " err=" + err;
        return s;
    }

    // ---------------- devices ----------------

    private void applyGrab() {
        boolean g = wantGrab && listener != null;
        synchronized (lock) {
            for (Dev d : devs.values()) d.setGrab(g);
        }
    }

    private void scanLoop() {
        while (alive) {
            try {
                scanOnce();
            } catch (Throwable t) {
                err = "scan: " + t;
                Log.e(TAG, "scan", t);
            }
            SystemClock.sleep(1500);
        }
    }

    private void scanOnce() {
        String[] names = new File("/dev/input").list();
        if (names == null) {
            err = "cannot list /dev/input";
            return;
        }
        Set<String> present = new HashSet<>();
        boolean changed = false;
        for (String n : names) {
            if (!n.startsWith("event")) continue;
            String p = "/dev/input/" + n;
            present.add(p);
            synchronized (lock) {
                if (devs.containsKey(p) || ignored.contains(p)) continue;
            }
            int fd = Native.open(p);
            if (fd < 0) continue; // permissions may appear later; retry next scan
            Dev d = Dev.parse(p, fd, Native.probe(fd));
            if (!d.gamepad) {
                Native.close(fd);
                synchronized (lock) {
                    ignored.add(p);
                }
                continue;
            }
            synchronized (lock) {
                devs.put(p, d);
            }
            d.setGrab(wantGrab && listener != null);
            Thread t = new Thread(() -> readLoop(d), "padtap-" + n);
            t.setDaemon(true);
            t.start();
            changed = true;
        }
        synchronized (lock) {
            ignored.retainAll(present);
        }
        if (changed) sendDevices();
    }

    private void readLoop(Dev d) {
        int[] buf = new int[64 * 3];
        boolean dirty = false;
        while (alive && d.run) {
            int n = Native.read(d.fd, buf, 250);
            if (n < 0) {
                if (n == -4 || n == -11) continue; // EINTR / EAGAIN
                break;
            }
            for (int i = 0; i < n; i++) {
                int type = buf[i * 3], code = buf[i * 3 + 1], val = buf[i * 3 + 2];
                if (type == EV_KEY) {
                    if (val == 1) {
                        synchronized (d) {
                            d.down.add(code);
                        }
                        dirty = true;
                    } else if (val == 0) {
                        synchronized (d) {
                            d.down.remove(code);
                        }
                        dirty = true;
                    }
                } else if (type == EV_ABS) {
                    if (d.onAbs(code, val)) dirty = true;
                } else if (type == EV_SYN && code == 0 && dirty) {
                    dirty = false;
                    publish();
                }
            }
        }
        synchronized (lock) {
            devs.remove(d.path);
        }
        d.setGrab(false);
        Native.close(d.fd);
        publish();
        sendDevices();
    }

    private void sendDevices() {
        IPadListener l = listener;
        if (l == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            for (Dev d : devs.values()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(d.name);
            }
        }
        try {
            l.onDevices(sb.toString());
        } catch (RemoteException ignored) {
        }
    }

    private void publish() {
        TreeSet<Integer> b = new TreeSet<>();
        float[] ax = new float[4];
        synchronized (lock) {
            for (Dev d : devs.values()) {
                synchronized (d) {
                    b.addAll(d.down);
                    for (int i = 0; i < 4; i++) if (Math.abs(d.v[i]) > Math.abs(ax[i])) ax[i] = d.v[i];
                    if (d.v[4] > 0.5f) b.add(0x138);
                    if (d.v[5] > 0.5f) b.add(0x139);
                    if (d.v[6] < -0.5f) b.add(0x222);
                    if (d.v[6] > 0.5f) b.add(0x223);
                    if (d.v[7] < -0.5f) b.add(0x220);
                    if (d.v[7] > 0.5f) b.add(0x221);
                }
            }
        }
        int[] arr = new int[b.size()];
        int i = 0;
        for (int x : b) arr[i++] = x;
        synchronized (pubLock) {
            if (Arrays.equals(arr, lastBtns)) {
                boolean near = true;
                for (int k = 0; k < 4; k++) if (Math.abs(ax[k] - lastAx[k]) > 0.003f) near = false;
                if (near) return;
            }
            lastBtns = arr;
            lastAx = ax;
            IPadListener l = listener;
            if (l != null) {
                try {
                    l.onState(arr, ax);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    static final class Dev {
        final String path;
        final int fd;
        String name = "?";
        volatile boolean run = true;
        boolean gamepad;
        private boolean grabbed;
        final Map<Integer, int[]> abs = new HashMap<>();
        final Set<Integer> down = new HashSet<>();
        int rx = -1, ry = -1, lt = -1, rt = -1;
        /** lx, ly, rx, ry, lt, rt, hatX, hatY */
        final float[] v = new float[8];

        Dev(String path, int fd) {
            this.path = path;
            this.fd = fd;
        }

        static Dev parse(String path, int fd, String info) {
            Dev d = new Dev(path, fd);
            Set<Integer> keys = new HashSet<>();
            if (info != null) {
                String[] lines = info.split("\n");
                if (lines.length > 0) d.name = lines[0];
                for (String l : lines) {
                    try {
                        if (l.startsWith("K:")) {
                            for (String s : l.substring(2).split(",")) if (!s.isEmpty()) keys.add(Integer.parseInt(s));
                        } else if (l.startsWith("A:")) {
                            for (String s : l.substring(2).split(",")) {
                                if (s.isEmpty()) continue;
                                String[] q = s.split(":");
                                d.abs.put(Integer.parseInt(q[0]),
                                        new int[]{Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3])});
                            }
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            boolean btn = false;
            for (int k : keys) if (k >= 0x120 && k <= 0x13e) btn = true;
            boolean axis = d.abs.containsKey(ABS_X) || d.abs.containsKey(ABS_HAT0X);
            d.gamepad = btn && axis;
            if (d.abs.containsKey(ABS_RX) && d.abs.containsKey(ABS_RY)) {
                d.rx = ABS_RX;
                d.ry = ABS_RY;
                d.lt = d.abs.containsKey(ABS_Z) ? ABS_Z : (d.abs.containsKey(ABS_BRAKE) ? ABS_BRAKE : -1);
                d.rt = d.abs.containsKey(ABS_RZ) ? ABS_RZ : (d.abs.containsKey(ABS_GAS) ? ABS_GAS : -1);
            } else if (d.abs.containsKey(ABS_Z) && d.abs.containsKey(ABS_RZ)) {
                d.rx = ABS_Z;
                d.ry = ABS_RZ;
                d.lt = d.abs.containsKey(ABS_BRAKE) ? ABS_BRAKE : -1;
                d.rt = d.abs.containsKey(ABS_GAS) ? ABS_GAS : -1;
            }
            return d;
        }

        boolean onAbs(int code, int val) {
            int idx;
            boolean trig = false, hat = false;
            if (code == ABS_X) idx = 0;
            else if (code == ABS_Y) idx = 1;
            else if (code == rx) idx = 2;
            else if (code == ry) idx = 3;
            else if (code == lt) {
                idx = 4;
                trig = true;
            } else if (code == rt) {
                idx = 5;
                trig = true;
            } else if (code == ABS_HAT0X) {
                idx = 6;
                hat = true;
            } else if (code == ABS_HAT0Y) {
                idx = 7;
                hat = true;
            } else return false;
            float f;
            int[] r = abs.get(code);
            if (hat) f = Math.signum(val);
            else if (r == null || r[1] == r[0]) f = 0;
            else {
                float t = (val - r[0]) / (float) (r[1] - r[0]);
                f = trig ? t : t * 2f - 1f;
            }
            synchronized (this) {
                v[idx] = f;
            }
            return true;
        }

        synchronized void setGrab(boolean g) {
            if (g == grabbed) return;
            if (Native.grab(fd, g) == 0) grabbed = g;
        }
    }
}
