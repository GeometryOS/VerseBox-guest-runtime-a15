#include <android/log.h>
#include <errno.h>
#include <execinfo.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

/*
 * The host applies its own seccomp policy to every process of this app, and guest
 * services call syscalls that policy does not allow: logd was killed by SIGSYS
 * (exit code 159) right after it bound /dev/socket/logd*, which left the three
 * sockets on disk with no listener and made every logcat fail with ECONNREFUSED.
 * Android expects a denied syscall to fail with ENOSYS, so emulate exactly that:
 * skip the trapped instruction and return -ENOSYS. Only a process that would
 * otherwise be killed by the signal is affected.
 */
static void sigsys_note(const char* text, unsigned long value) {
    char line[80];
    size_t length = 0;
    while (text[length] != '\0' && length < sizeof(line) - 24) {
        line[length] = text[length];
        ++length;
    }
    char digits[24];
    size_t count = 0;
    if (value == 0) digits[count++] = '0';
    while (value > 0) {
        digits[count++] = (char)('0' + (int)(value % 10));
        value /= 10;
    }
    while (count > 0) line[length++] = digits[--count];
    line[length++] = '\n';
    ssize_t ignored;
    do { ignored = write(STDERR_FILENO, line, length); } while (ignored < 0 && errno == EINTR);
}

static void sigsys_handler(int signal, siginfo_t* info, void* context) {
    (void)signal;
    if (info != NULL) sigsys_note("A15VM seccomp blocked syscall ", (unsigned long)info->si_syscall);
#if defined(__aarch64__)
    ucontext_t* uc = (ucontext_t*)context;
    /* x0 carries the syscall return value; pc has to skip the trapped svc. */
    uc->uc_mcontext.regs[0] = (unsigned long)(-ENOSYS);
    uc->uc_mcontext.pc += 4;
#else
    (void)context;
#endif
}

__attribute__((constructor)) static void install_sigsys_handler(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = sigsys_handler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSYS, &action, NULL);
}

static int emit(int priority, const char* tag, const char* text) {
    int saved = errno;
    char line[4096];
    int length = snprintf(line, sizeof(line), "GUEST_LOG %d %s: %s\n", priority, tag ? tag : "", text ? text : "");
    if (length >= (int)sizeof(line)) {
        length = sizeof(line) - 1;
        line[length - 1] = '\n';
    }
    // stdout and stderr share a log file. Keep each native record in one write
    // so its prefix cannot split a Java startup identity record.
    if (length > 0) while (write(STDERR_FILENO, line, length) < 0 && errno == EINTR) { }
    errno = saved;
    return 1;
}

int __android_log_write(int priority, const char* tag, const char* text) {
    return emit(priority, tag, text);
}

int __android_log_buf_write(int buffer, int priority, const char* tag, const char* text) {
    (void) buffer;
    return __android_log_write(priority, tag, text);
}

int __android_log_vprint(int priority, const char* tag, const char* format, va_list args) {
    int saved = errno;
    char text[4096];
    vsnprintf(text, sizeof(text), format, args);
    errno = saved;
    return emit(priority, tag, text);
}

int __android_log_print(int priority, const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int result = __android_log_vprint(priority, tag, format, args);
    va_end(args);
    return result;
}

int __android_log_buf_print(int buffer, int priority, const char* tag, const char* format, ...) {
    (void) buffer;
    va_list args;
    va_start(args, format);
    int result = __android_log_vprint(priority, tag, format, args);
    va_end(args);
    return result;
}

void __android_log_assert(const char* condition, const char* tag, const char* format, ...) {
    if (format) {
        va_list args;
        va_start(args, format);
        __android_log_vprint(7, tag, format, args);
        va_end(args);
    } else __android_log_write(7, tag, condition);
    abort();
}

// API 30 liblog message API. Deliberately does NOT define
// __android_log_set_logger(): the image's liblog keeps that state, and
// dispatching through an installed logger re-enters this shim (stack overflow,
// SIGSEGV at ART startup). Messages are emitted directly instead.
static __android_aborter_function guest_aborter;
static int32_t guest_minimum_priority = ANDROID_LOG_INFO;

void __android_log_logd_logger(const struct __android_log_message* log_message) {
    if (!log_message) return;
    emit(log_message->priority, log_message->tag, log_message->message);
}

void __android_log_stderr_logger(const struct __android_log_message* log_message) {
    __android_log_logd_logger(log_message);
}

void __android_log_write_log_message(struct __android_log_message* log_message) {
    __android_log_logd_logger(log_message);
}

void __android_log_set_aborter(__android_aborter_function aborter) { guest_aborter = aborter; }

void __android_log_call_aborter(const char* abort_message) {
    if (guest_aborter) guest_aborter(abort_message); else abort();
}

int __android_log_is_loggable(int prio, const char* tag, int default_prio) {
    (void) tag; (void) default_prio;
    return prio >= guest_minimum_priority;
}

int __android_log_is_loggable_len(int prio, const char* tag, size_t len, int default_prio) {
    (void) tag; (void) len; (void) default_prio;
    return prio >= guest_minimum_priority;
}

int32_t __android_log_set_minimum_priority(int32_t priority) {
    int32_t previous = guest_minimum_priority;
    guest_minimum_priority = priority;
    return previous;
}

int32_t __android_log_get_minimum_priority(void) { return guest_minimum_priority; }

void __android_log_set_default_tag(const char* tag) { (void) tag; }

/*
 * Round 71: system_server dies with SIGABRT inside StartInputManagerService,
 * right after inputflinger's Rust and stats pieces come up. The captured pc is
 * libc's async_safe_format_fd_va_list + its tail call to abort(), i.e. the
 * __fortify_fatal path: bionic prints those messages to /dev/kmsg only, which a
 * container cannot read, so the abort text never reaches this log and the crash
 * gives no reason.
 *
 * These interposers see the exact numbers FORTIFY itself checks. They keep the
 * check's intent (never write past the object size the caller declared) and print
 * one line per distinct failure, so the next real-device log names the call site
 * instead of another bare sig=6.
 *
 * They only apply to cross-library calls: libc's own internal uses bind within
 * libc and are untouched.
 */
static int fortify_reports = 0;

static int fortify_note(const char* what, size_t claim, size_t actual,
                        const char* detail) {
    if (fortify_reports++ >= 8) return 0;
    char text[512];
    int length = snprintf(text, sizeof(text),
                          "%s: claim=%zu actual=%zu %s%s", what, claim, actual,
                          detail ? "format=" : "",
                          detail ? detail : "");
    if (length <= 0) return 0;
    return emit(4, "FORTIFY", text);
}

int __vsnprintf_chk(char* dst, size_t size, int flags, size_t dst_len,
                    const char* format, va_list va) {
    (void) flags;
    if (dst_len != (size_t) -1 && size > dst_len) {
        fortify_note("vsnprintf", size, dst_len, format);
        size = dst_len;
    }
    if (size == 0) return 0;
    return vsnprintf(dst, size, format, va);
}

int __snprintf_chk(char* dst, size_t size, int flags, size_t dst_len,
                   const char* format, ...) {
    va_list va;
    va_start(va, format);
    int result = __vsnprintf_chk(dst, size, flags, dst_len, format, va);
    va_end(va);
    return result;
}

void* __memcpy_chk(void* dst, const void* src, size_t len, size_t dst_len) {
    if (dst_len != (size_t) -1 && len > dst_len) {
        fortify_note("memcpy", len, dst_len, NULL);
        len = dst_len;
    }
    return memcpy(dst, src, len);
}

/*
 * Round 71 (final): the real abort is the stack protector, not FORTIFY-source.
 * bionic's __stack_chk_fail() calls __fortify_fatal("stack corruption detected"),
 * which is exactly the async_safe_format_fd_va_list + tail-call-abort shape the
 * crash line has shown every time. The three __*_chk interposers above never
 * fired on the real device (instance 1793211042697), which confirms this.
 *
 * The guard value is printed with the unwound frames: a guard that differs from
 * the one this libc seeded means the mismatch is between our TLS/guard and an
 * image library built against a different bionic, i.e. a false positive; a
 * plausible guard with a sane stack means a real overflow in the caller.
 *
 * Returning instead of aborting is deliberate: system_server must get past
 * StartInputManagerService so the rest of startOtherServices becomes observable.
 * A genuinely corrupted frame still faults immediately afterwards and the next
 * crash line says where.
 */
extern uintptr_t __stack_chk_guard;
static int stack_chk_reports = 0;

void __stack_chk_fail(void) {
    // Round 115: report EVERY failure, not just the first two. The WebView
    // browser process dies with SIGTRAP (133) at the canary-failure stub
    // ("brk #0" immediately after this call) in libwebviewchromium, i.e. this
    // interposer returned into a path the compiler marked noreturn, and the old
    // "first two only" cap made that third (and fatal) failure completely
    // silent -- log instance 1793503945104 had no stack_chk line at all before
    // the SIGTRAP. The caller address is printed because Chromium is built
    // without frame pointers, so backtrace() alone cannot name it.
    ++stack_chk_reports;
    {
        void* frames[32];
        int count = backtrace(frames, 32);
        char line[224];
        int written = snprintf(line, sizeof(line),
                               "A9VM stack_chk_fail n=%d caller=0x%lx frames=%d guard_slot=0x%lx guard=0x%lx\n",
                               stack_chk_reports,
                               (unsigned long)(uintptr_t)__builtin_return_address(0),
                               count,
                               (unsigned long)(uintptr_t)&__stack_chk_guard,
                               (unsigned long)__stack_chk_guard);
        if (written > 0) write(STDERR_FILENO, line, (size_t) written);
        for (int index = 0; index < count && index < 24; ++index) {
            written = snprintf(line, sizeof(line),
                               "A9VM stack_chk_frame %d = 0x%lx\n", index,
                               (unsigned long)(uintptr_t)frames[index]);
            if (written > 0) write(STDERR_FILENO, line, (size_t) written);
        }
    }
}

/*
 * Round 154: logd aborts before it can serve anyone because this container has no
 * capabilities at all (it runs as an app uid):
 *   GUEST_LOG 7 : cap_set_proc() failed: Operation not permitted
 *   A9VM abort caller=liba15vm-image-…so+0xaa6f8   (then sig=6)
 * logd's init path drops capabilities through libcap; the call can never succeed
 * here, and failing it is what kills the daemon. Answer the libcap entry points
 * locally so the "drop everything" step becomes a no-op. libcap's cap_t is opaque,
 * so a static dummy is enough for the only pattern used here (get, set flag, set
 * proc, free).
 */
typedef void* guest_cap_t;
static char guest_cap_dummy[64];

guest_cap_t cap_get_proc(void) { return guest_cap_dummy; }
guest_cap_t cap_get_file(const char* path) { (void) path; return guest_cap_dummy; }
guest_cap_t cap_get_pid(int pid) { (void) pid; return guest_cap_dummy; }
guest_cap_t cap_from_text(const char* text) { (void) text; return guest_cap_dummy; }
guest_cap_t cap_dup(guest_cap_t caps) { (void) caps; return guest_cap_dummy; }
int cap_set_proc(guest_cap_t caps) { (void) caps; return 0; }
int cap_set_file(const char* path, guest_cap_t caps) { (void) path; (void) caps; return 0; }
int cap_set_flag(guest_cap_t caps, int type, int count, const int* values, int flag) {
    (void) caps; (void) type; (void) count; (void) values; (void) flag;
    return 0;
}
int cap_clear(guest_cap_t caps) { (void) caps; return 0; }
int cap_clear_flag(guest_cap_t caps, int type) { (void) caps; (void) type; return 0; }
int cap_free(void* caps) { (void) caps; return 0; }
int cap_get_flag(guest_cap_t caps, int value, int type, int* result) {
    (void) caps; (void) value; (void) type;
    if (result) *result = 0;
    return 0;
}
char* cap_to_text(guest_cap_t caps, long* length) {
    (void) caps;
    if (length) *length = 0;
    return (char*) "";
}

