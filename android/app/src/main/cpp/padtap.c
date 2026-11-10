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
