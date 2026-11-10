package io.padtap;

/** evdev access, implemented in padtap.c. Used only inside the Shizuku service process. */
final class Native {
    private Native() {}

    static native int open(String path);

    static native void close(int fd);

    static native int grab(int fd, boolean on);

    static native String probe(int fd);

    static native int read(int fd, int[] out, int timeoutMs);

    // test helpers (SelfTest)
    static native int uinputCreate();

    static native int uinputEmit(int fd, int type, int code, int value);

    static native void uinputDestroy(int fd);

    static boolean loaded() {
        try {
            open("/nonexistent");
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
}
