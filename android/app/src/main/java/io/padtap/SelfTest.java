package io.padtap;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CI self-test. Run as the shell user (same privileges as Shizuku):
 * CLASSPATH=base.apk app_process -Dpadtap.lib=.../libpadtap.so -Dpadtap.tap=X,Y /system/bin io.padtap.SelfTest
 * Creates a virtual gamepad, checks that PadService reads/grabs it, and injects touches
 * (ProbeActivity logs what it receives).
 */
public final class SelfTest {
    private static int fails = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    private static boolean has(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static void emit(int fd, int type, int code, int val) throws InterruptedException {
        Native.uinputEmit(fd, type, code, val);
        Native.uinputEmit(fd, 0, 0, 0);
        Thread.sleep(250);
    }

    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("padtap.lib"));
        String[] tap = System.getProperty("padtap.tap", "500,800").split(",");
        float cx = Float.parseFloat(tap[0]), cy = Float.parseFloat(tap[1]);
        System.out.println("uid=" + android.os.Process.myUid());

        int u = Native.uinputCreate();
        check(u >= 0, "uinput create (" + u + ")");
        Thread.sleep(1500);

        AtomicReference<int[]> btn = new AtomicReference<>(new int[0]);
        AtomicReference<float[]> ax = new AtomicReference<>(new float[4]);
        AtomicReference<String> devs = new AtomicReference<>("");
        PadService svc = new PadService(null);
        svc.setListener(new IPadListener.Stub() {
            @Override
            public void onState(int[] b, float[] a) {
                btn.set(b);
                ax.set(a);
            }

            @Override
            public void onDevices(String d) {
                devs.set(d);
                System.out.println("devices: " + d.replace("\n", " | "));
            }
        });
        String info = svc.info();
        System.out.println("info: " + info);
        check(info.contains("inj=ok"), "injector init");
        check(!info.contains("err="), "no service error");
        Thread.sleep(3000);
        check(devs.get().contains("PadTap Test Pad"), "virtual pad detected");

        if (u >= 0) {
            emit(u, 1, 0x130, 1);
            check(has(btn.get(), 0x130), "A press seen");
            emit(u, 1, 0x130, 0);
            check(!has(btn.get(), 0x130), "A release seen");
            emit(u, 3, 0, 32767);
            check(ax.get()[0] > 0.9f, "left stick right (" + ax.get()[0] + ")");
            emit(u, 3, 0, 0);
            check(Math.abs(ax.get()[0]) < 0.05f, "left stick center (" + ax.get()[0] + ")");
            emit(u, 3, 4, -32768);
            check(ax.get()[3] < -0.9f, "right stick up (" + ax.get()[3] + ")");
            emit(u, 3, 4, 0);
            emit(u, 3, 0x10, -1);
            check(has(btn.get(), 0x222), "hat left -> dpad left");
            emit(u, 3, 0x10, 0);
            check(!has(btn.get(), 0x222), "hat released");

            // While grabbed, Android (ProbeActivity) must NOT see B.
            System.out.println("MARK grabbed-B");
            emit(u, 1, 0x131, 1);
            emit(u, 1, 0x131, 0);
            check(!has(btn.get(), 0x131), "B release seen");
            // Not grabbed: Android should see X.
            svc.setGrab(false);
            Thread.sleep(400);
            System.out.println("MARK ungrabbed-X");
            emit(u, 1, 0x133, 1);
            emit(u, 1, 0x133, 0);
            svc.setGrab(true);
            Thread.sleep(300);
        }

        // Touch injection: single drag, then a second finger.
        svc.touchDown(0, cx, cy);
        Thread.sleep(120);
        svc.touchMove(0, cx + 60, cy + 30);
        Thread.sleep(120);
        svc.touchDown(1, cx - 120, cy - 120);
        Thread.sleep(120);
        svc.touchUp(1);
        Thread.sleep(120);
        svc.touchUp(0);
        Thread.sleep(400);
        String info2 = svc.info();
        System.out.println("info after inject: " + info2);
        check(info2.contains("inj=ok"), "injection without exceptions");

        if (u >= 0) Native.uinputDestroy(u);
        Thread.sleep(2000);
        check(!devs.get().contains("PadTap Test Pad"), "virtual pad removal seen");
        System.out.println("SELFTEST fails=" + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
