package io.padtap;

/** evdev access, implemented in padtap.c. Used only inside the Shizuku service process. */
final class Native {
    private Native() {}

    static native int open(String path);

    static native void close(int fd);

    static native int grab(int fd, boolean on);

    static native String probe(int fd);

    static native int read(int fd, int[] out, int timeoutMs);
}
