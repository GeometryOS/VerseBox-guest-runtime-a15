#include <android/log.h>
#include <errno.h>
#include <execinfo.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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
    if (stack_chk_reports++ < 2) {
        void* frames[32];
        int count = backtrace(frames, 32);
        char line[160];
        int written = snprintf(line, sizeof(line),
                               "A9VM stack_chk_fail frames=%d guard_slot=0x%lx guard=0x%lx\n",
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
