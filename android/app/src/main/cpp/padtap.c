// PadTap native helper: evdev access (open / probe / grab / read) for the Shizuku-side service.
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define LBITS (sizeof(long) * 8)
#define NLONGS(x) (((x) + LBITS - 1) / LBITS)

static int test_bit(int bit, const unsigned long *arr) {
    return (int)((arr[bit / LBITS] >> (bit % LBITS)) & 1UL);
}

JNIEXPORT jint JNICALL Java_io_padtap_Native_open(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    int e = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd < 0 ? -e : fd;
}

JNIEXPORT void JNICALL Java_io_padtap_Native_close(JNIEnv *env, jclass cls, jint fd) {
    if (fd >= 0) close(fd);
}

JNIEXPORT jint JNICALL Java_io_padtap_Native_grab(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) < 0 ? -errno : 0;
}

// Returns "name\nK:code,code,...\nA:code:min:max:flat,..."
JNIEXPORT jstring JNICALL Java_io_padtap_Native_probe(JNIEnv *env, jclass cls, jint fd) {
    char name[256];
    memset(name, 0, sizeof name);
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) strcpy(name, "?");
    for (char *c = name; *c; c++) {
        if ((unsigned char)*c >= 0x80 || *c == '\n' || *c == '\r') *c = '?';
    }
    unsigned long keyb[NLONGS(KEY_CNT)];
    unsigned long absb[NLONGS(ABS_CNT)];
    memset(keyb, 0, sizeof keyb);
    memset(absb, 0, sizeof absb);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keyb), keyb);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof absb), absb);

    char buf[8192];
    int n = snprintf(buf, sizeof buf, "%s\nK:", name);
    for (int k = BTN_MISC; k < KEY_CNT && n < (int)sizeof(buf) - 64; k++) {
        if (test_bit(k, keyb)) n += snprintf(buf + n, sizeof(buf) - n, "%d,", k);
    }
    n += snprintf(buf + n, sizeof(buf) - n, "\nA:");
    for (int a = 0; a < ABS_CNT && n < (int)sizeof(buf) - 64; a++) {
        if (!test_bit(a, absb)) continue;
        struct input_absinfo ai;
        if (ioctl(fd, EVIOCGABS(a), &ai) == 0) {
            n += snprintf(buf + n, sizeof(buf) - n, "%d:%d:%d:%d,", a, ai.minimum, ai.maximum, ai.flat);
        }
    }
    return (*env)->NewStringUTF(env, buf);
}

// Reads up to len(out)/3 events as (type, code, value) triples.
// Returns count, 0 on timeout, or -errno.
JNIEXPORT jint JNICALL Java_io_padtap_Native_read(JNIEnv *env, jclass cls, jint fd, jintArray out, jint timeoutMs) {
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr < 0) return -errno;
    if (pr == 0) return 0;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -ENODEV;

    struct input_event ev[64];
    jsize cap = (*env)->GetArrayLength(env, out) / 3;
    if (cap > 64) cap = 64;
    ssize_t r = read(fd, ev, sizeof(struct input_event) * (size_t)cap);
    if (r < 0) return -errno;
    int cnt = (int)(r / (ssize_t)sizeof(struct input_event));
    jint tmp[64 * 3];
    for (int i = 0; i < cnt; i++) {
        tmp[i * 3] = ev[i].type;
        tmp[i * 3 + 1] = ev[i].code;
        tmp[i * 3 + 2] = ev[i].value;
    }
    (*env)->SetIntArrayRegion(env, out, 0, cnt * 3, tmp);
    return cnt;
}

// ---------------- test helpers (virtual gamepad via uinput; used by SelfTest only) ----------------
#include <linux/uinput.h>

JNIEXPORT jint JNICALL Java_io_padtap_Native_uinputCreate(JNIEnv *env, jclass cls) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    int keys[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_SELECT, BTN_START,
                  BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (unsigned i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);
    int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_HAT0X, ABS_HAT0Y};
    for (unsigned i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) ioctl(fd, UI_SET_ABSBIT, axes[i]);
    struct uinput_user_dev u;
    memset(&u, 0, sizeof u);
    strcpy(u.name, "PadTap Test Pad");
    u.id.bustype = BUS_USB;
    u.id.vendor = 0x1234;
    u.id.product = 0x5678;
    u.id.version = 1;
    for (int i = 0; i < 4; i++) {
        u.absmin[axes[i]] = -32768;
        u.absmax[axes[i]] = 32767;
    }
    u.absmin[ABS_HAT0X] = -1; u.absmax[ABS_HAT0X] = 1;
    u.absmin[ABS_HAT0Y] = -1; u.absmax[ABS_HAT0Y] = 1;
    if (write(fd, &u, sizeof u) != (ssize_t)sizeof u) { int e = errno; close(fd); return -e; }
    if (ioctl(fd, UI_DEV_CREATE) < 0) { int e = errno; close(fd); return -e; }
    return fd;
}

JNIEXPORT jint JNICALL Java_io_padtap_Native_uinputEmit(JNIEnv *env, jclass cls, jint fd, jint type, jint code, jint value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = value;
    return write(fd, &ev, sizeof ev) == (ssize_t)sizeof ev ? 0 : -errno;
}

JNIEXPORT void JNICALL Java_io_padtap_Native_uinputDestroy(JNIEnv *env, jclass cls, jint fd) {
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}
