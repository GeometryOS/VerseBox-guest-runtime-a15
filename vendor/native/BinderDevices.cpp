#include "Driver.h"
#include <dlfcn.h>
#include <errno.h>
#include <execinfo.h>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

namespace {
// pthread key instead of thread_local: A9's linker has no R_AARCH64_TLSDESC
// handler and its R_AARCH64_TLS_TPREL64 branch writes nothing, so a
// shared-library TLS variable would jump to 0 or corrupt the thread pointer.
pthread_key_t insideDriverKey;
pthread_once_t insideDriverOnce = PTHREAD_ONCE_INIT;
void createInsideDriverKey() { pthread_key_create(&insideDriverKey, nullptr); }
bool insideDriver() {
    pthread_once(&insideDriverOnce, createInsideDriverKey);
    return pthread_getspecific(insideDriverKey) != nullptr;
}
void setInsideDriver(bool value) {
    pthread_once(&insideDriverOnce, createInsideDriverKey);
    pthread_setspecific(insideDriverKey, value ? reinterpret_cast<void*>(1) : nullptr);
}

// Keep a one-line record of the fault address. Guest processes have no
// debuggerd handler, so without this a SIGSEGV leaves nothing in the log.
//
// The absolute pc/lr of an ASLR'd module are useless on their own (real device
// 2026-10-28: "pc=7681364370 lr=768136436c" with no way to say which function),
// so every crash line now prints "path+offset" for pc, lr and every frame.
//
// The guest maps ~2,400 vmas (>200 KiB of text). A single 64 KiB read leaves
// the table truncated and the lookup outside it -- that is why round 40 printed
// "base=0x0". The table is therefore read into a static buffer (bss, not the
// stack) and looked up by range; nothing here allocates or calls into libdl.
constexpr size_t kMapsCapacity = 512 * 1024;
char mapsText[kMapsCapacity];
size_t mapsLength = 0;

static void loadMaps() {
    int fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return;
    mapsLength = 0;
    while (mapsLength < kMapsCapacity - 1) {
        ssize_t size = read(fd, mapsText + mapsLength, kMapsCapacity - 1 - mapsLength);
        if (size <= 0) break;
        mapsLength += static_cast<size_t>(size);
    }
    close(fd);
    mapsText[mapsLength] = 0;
}

static const char* skipSpaces(const char* cursor) {
    while (*cursor == ' ') ++cursor;
    return cursor;
}

// Formats "<module>+0x<offset>" for any address, using the mapping that
// contains it. Falls back to "?0x<addr>" when the address is not mapped.
// One /proc/self/maps line is "start-end perms fileoffset dev inode path".
// Round 68: the old field walk read the FILE OFFSET from the "perms" token
// (strtoul("r-xp") == 0), so `base` collapsed to the mapping start and every
// printed offset was "pc - mapping_start". For any module whose executable
// segment is not its first mapping that number is larger than the whole file
// (real device 2026-10-29: "liba15vm-image-...so+0x36bc8" for a 102,296-byte
// library), which is exactly the same class of mistake that cost round 67 a
// whole session (NT_FILE base vs file page). Parse the real offset.
enum class MapsField { kStart, kEnd, kOffset, kName };

static bool parseMapsLine(const char* line, uintptr_t* start, uintptr_t* end,
                          uintptr_t* offset, const char** name) {
    char* cursor = nullptr;
    *start = strtoul(line, &cursor, 16);
    if (cursor == nullptr || *cursor != '-') return false;
    *end = strtoul(cursor + 1, &cursor, 16);
    // perms
    cursor = const_cast<char*>(skipSpaces(cursor));
    cursor = const_cast<char*>(strchr(cursor, ' '));
    if (cursor == nullptr) return false;
    // file offset (hex; "00000000" for anonymous mappings)
    cursor = const_cast<char*>(skipSpaces(cursor));
    *offset = strtoul(cursor, &cursor, 16);
    // dev
    cursor = const_cast<char*>(skipSpaces(cursor));
    cursor = const_cast<char*>(strchr(cursor, ' '));
    if (cursor == nullptr) return false;
    // inode
    cursor = const_cast<char*>(skipSpaces(cursor));
    cursor = const_cast<char*>(strchr(cursor, ' '));
    if (cursor == nullptr) return false;
    *name = skipSpaces(cursor);
    return true;
}

static const char* mapsLineFor(uintptr_t value) {
    for (const char* line = mapsText; line != nullptr && *line != 0;) {
        const char* next = strchr(line, '\n');
        uintptr_t start = 0, end = 0, offset = 0;
        const char* name = nullptr;
        if (parseMapsLine(line, &start, &end, &offset, &name) && value >= start && value < end) {
            return line;
        }
        line = next != nullptr ? next + 1 : nullptr;
    }
    return nullptr;
}

static void formatAddress(char* out, size_t outSize, uintptr_t value) {
    for (const char* line = mapsText; line != nullptr && *line != 0;) {
        const char* next = strchr(line, '\n');
        uintptr_t start = 0, end = 0, offset = 0;
        const char* name = nullptr;
        if (parseMapsLine(line, &start, &end, &offset, &name) && value >= start && value < end) {
            const char* slash = strrchr(name, '/');
            if (slash != nullptr) name = slash + 1;
            size_t nameLength = strcspn(name, " \n");
            if (nameLength == 0) { name = "[anon]"; nameLength = 6; }
            snprintf(out, outSize, "%.*s+0x%lx", static_cast<int>(nameLength), name,
                     static_cast<unsigned long>(value - (start - offset)));
            return;
        }
        line = next != nullptr ? next + 1 : nullptr;
    }
    // The maps snapshot above is taken when this library is loaded, so an
    // address that only exists in a mapping created later (JIT code, a library
    // loaded after startup, a thread stack) prints as "?0x...". That is what
    // round 72b produced for both the abort caller and the abort pc, which is
    // useless for attribution. Fall back to the dynamic linker, which knows the
    // module base of everything it loaded; an offset into a module is enough to
    // disassemble the call site even when the ROM library is stripped.
    Dl_info info;
    if (dladdr(reinterpret_cast<void*>(value), &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = strrchr(info.dli_fname, '/');
        const char* name = slash != nullptr ? slash + 1 : info.dli_fname;
        uintptr_t offset = value - reinterpret_cast<uintptr_t>(info.dli_fbase);
        snprintf(out, outSize, "%s+0x%lx", name, static_cast<unsigned long>(offset));
        return;
    }
    snprintf(out, outSize, "?0x%lx", static_cast<unsigned long>(value));
}

// Remaining space in a fixed buffer. snprintf returns the length it WOULD have
// written, so `cursor += written` can walk past `end`; the old code then passed
// a negative (huge unsigned) size to the next snprintf and the guest libc's
// FORTIFY aborted inside the crash handler itself:
//   FORTIFY: vsnprintf: size 18446744073709551572 > SSIZE_MAX
// which turned a SIGABRT (134) into a recursive SIGABRT storm ending in 139.
// Every write below now uses this helper and clamps the cursor to `end`.
static size_t remaining(const char* cursor, const char* end) {
    return cursor < end ? static_cast<size_t>(end - cursor) : 0;
}


static void appendAddress(char** cursor, char* end, uintptr_t value) {
    size_t space = remaining(*cursor, end);
    if (!space) return;
    char text[160];
    formatAddress(text, sizeof(text), value);
    int written = snprintf(*cursor, space, " %s", text);
    if (written <= 0) return;
    if (static_cast<size_t>(written) < space) *cursor += written;
    else *cursor = end;
}

// A SIGABRT often arrives on several threads of the same process at once (round
// 46/47: surfaceflinger printed ~15 interleaved blocks). Interleaved writes make
// the line unreadable, and re-entering this handler while it is already walking
// frames is what turned a 134 into a 139. Only the first signal prints; every
// later one terminates immediately with the same 128+sig status.
static volatile sig_atomic_t inHandler = 0;

static void crashHandler(int sig, siginfo_t* info, void* context) {
    if (inHandler) syscall(__NR_exit_group, 128 + sig);
    inHandler = 1;
    ucontext_t* uc = static_cast<ucontext_t*>(context);
    uintptr_t pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
    uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
    uintptr_t sp = static_cast<uintptr_t>(uc->uc_mcontext.sp);
    uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
    uintptr_t fault = reinterpret_cast<uintptr_t>(info->si_addr);

    loadMaps();

    char buffer[2048];
    char* cursor = buffer;
    char* end = buffer + sizeof(buffer);
    char where[192];
    formatAddress(where, sizeof(where), pc);
    char lrwhere[192];
    formatAddress(lrwhere, sizeof(lrwhere), lr);
    // Round 68: print the RAW pc/lr as well. "module+offset" alone is only as
    // good as the maps parse, and one bad offset already sent rounds 66/67 after
    // the wrong library. With raw addresses the frame can always be resolved
    // offline against the same .so (the guest modules are in the APK's lib dir).
    int written = snprintf(cursor, remaining(cursor, end),
                           "A9VM bdev crash sig=%d code=%d pid=%d fault=0x%lx rawpc=0x%lx rawlr=0x%lx sp=0x%lx pc=%s lr=%s\n",
                           sig, int(info->si_code), int(info->si_pid),
                           static_cast<unsigned long>(fault),
                           static_cast<unsigned long>(pc), static_cast<unsigned long>(lr),
                           static_cast<unsigned long>(sp), where, lrwhere);
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    write(2, buffer, static_cast<size_t>(cursor - buffer));

    // The verbatim /proc/self/maps line that contains pc: start-end, perms,
    // file offset, dev, inode and path. Everything needed to redo the
    // "value -> module+offset" reduction by hand is on this one line.
    cursor = buffer;
    const char* pcLine = mapsLineFor(pc);
    if (pcLine == nullptr) {
        written = snprintf(cursor, remaining(cursor, end), "A9VM bdev map <none>\n");
    } else {
        size_t lineLength = strcspn(pcLine, "\n");
        written = snprintf(cursor, remaining(cursor, end), "A9VM bdev map %.*s\n",
                           static_cast<int>(lineLength), pcLine);
    }
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    write(2, buffer, static_cast<size_t>(cursor - buffer));

    // Register file and the faulting instruction: enough to tell "the callee
    // returned null and we dereferenced it" from a wild pointer.
    cursor = buffer;
    written = snprintf(cursor, remaining(cursor, end), "A9VM bdev regs");
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    for (int index = 0; index < 9; ++index) {
        written = snprintf(cursor, remaining(cursor, end), " x%d=0x%lx", index,
                           static_cast<unsigned long>(uc->uc_mcontext.regs[index]));
        if (written > 0) {
            if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
            else { cursor = end; break; }
        }
    }
    written = snprintf(cursor, remaining(cursor, end), " x19=0x%lx\n",
                       static_cast<unsigned long>(uc->uc_mcontext.regs[19]));
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    write(2, buffer, static_cast<size_t>(cursor - buffer));

    // Frame-pointer walk. ART and the bridges are built with frame pointers; a
    // bogus fp is skipped instead of dereferenced (a second fault inside the
    // handler would lose the whole line).
    cursor = buffer;
    written = snprintf(cursor, remaining(cursor, end), "A9VM bdev bt");
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    for (int frame = 0; frame < 16; ++frame) {
        if (fp < sp || fp - sp > (1u << 21) || (fp & 7u) != 0) break;
        uintptr_t pair[2];
        memcpy(pair, reinterpret_cast<void*>(fp), sizeof(pair));
        appendAddress(&cursor, end, pair[1]);
        if (pair[0] <= fp) break;
        fp = pair[0];
    }
    written = snprintf(cursor, remaining(cursor, end), "\n");
    if (written > 0) {
        if (static_cast<size_t>(written) < remaining(cursor, end)) cursor += written;
        else cursor = end;
    }
    write(2, buffer, static_cast<size_t>(cursor - buffer));
    // Raw syscall, like every other call in this handler: `_exit` goes through
    // the PLT, and resolving it inside a SIGABRT handler is exactly the kind of
    // re-entry that produced the 15-line SIGABRT storm of round 46.
    syscall(__NR_exit_group, 128 + sig);
}

static void trace(const char* text) { write(2, text, strlen(text)); }

// libc runs its own constructors (stdio, locale, isatty...) before this shim's
// constructors, and those call close/ioctl/mmap. liba9binder's globals (the
// driver map) are not constructed yet at that point, so every override must
// pass through until this shim is fully initialized. Without this guard the
// first libc-constructor hit lands in a not-yet-constructed std::map and
// segfaults before the program's main() is reached.
bool ready = false;
struct Activator {
    Activator() {
        // Only install the handlers here. The mapping table is read inside the
        // handler: most guest libraries (and any second copy the loader maps)
        // appear long after this constructor, so a snapshot taken now would
        // miss exactly the addresses a late crash needs.
        ready = true;
        struct sigaction action {};
        action.sa_sigaction = crashHandler;
        action.sa_flags = SA_SIGINFO;
        sigaction(SIGSEGV, &action, nullptr);
        sigaction(SIGBUS, &action, nullptr);
        sigaction(SIGILL, &action, nullptr);
        // Round 45 C2: surfaceflinger dies with exit code 134 (128+SIGABRT) after
        // "RenderEngine: Unable to generate SkImage", and its own log has no fatal
        // line -- so the abort carries no message and the process leaves nothing
        // behind. Catching SIGABRT here prints the faulting pc/frames (the caller
        // of abort(), e.g. a Skia SK_ABORT or a LOG_ALWAYS_FATAL path) and still
        // terminates with 128+SIGABRT, so the host's exit-code diagnostic keeps
        // reporting the same 134. SIGQUIT is deliberately NOT caught: ART's
        // thread dump depends on it.
        sigaction(SIGABRT, &action, nullptr);
    }
} activator;

struct DriverCall {
    DriverCall() { setInsideDriver(true); }
    ~DriverCall() { setInsideDriver(false); }
};

const char* device(const char* path) {
    if (!path || insideDriver()) return nullptr;
    if (!strcmp(path, "/dev/binder")) return a10binder_default_driver();
    if (!strcmp(path, "/dev/hwbinder")) return a10binder_default_hwbinder_driver();
    return nullptr;
}

int openDevice(const char* path, int flags, mode_t mode) {
    const char* endpoint = device(path);
    if (!endpoint) return openat(AT_FDCWD, path, flags, mode);
    if ((flags & O_ACCMODE) != O_RDWR) { errno = EINVAL; return -1; }
    DriverCall call;
    return a10binder_open(endpoint);
}
}

extern "C" int open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list args;
        va_start(args, flags);
        mode = va_arg(args, int);
        va_end(args);
    }
    if (!ready) return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, flags, mode));
    return openDevice(path, flags, mode);
}

extern "C" int __open_2(const char* path, int flags) {
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { errno = EINVAL; return -1; }
    if (!ready) return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, flags, 0));
    return openDevice(path, flags, 0);
}

extern "C" int access(const char* path, int mode) {
    if (!ready) return static_cast<int>(syscall(__NR_faccessat, AT_FDCWD, path, mode));
    const char* endpoint = device(path);
    return faccessat(AT_FDCWD, endpoint ? endpoint : path, mode, 0);
}

extern "C" int ioctl(int fd, int request, ...) {
    va_list args;
    va_start(args, request);
    void* argument = va_arg(args, void*);
    va_end(args);
    if (!ready) return static_cast<int>(syscall(__NR_ioctl, fd, static_cast<unsigned int>(request), argument));
    if (!insideDriver() && a10binder_is_driver(fd)) {
        DriverCall call;
        int result = a10binder_ioctl(fd, static_cast<unsigned int>(request), argument);
        static int traceCount = 0;
        if (static_cast<unsigned int>(request) == BINDER_WRITE_READ && traceCount < 24) {
            char line[128];
            int length = snprintf(line, sizeof(line), "bdev writeRead fd=%d ret=%d errno=%d\n", fd, result, errno);
            if (length > 0) write(2, line, static_cast<size_t>(length));
            ++traceCount;
        }
        if (result == 0 && static_cast<unsigned int>(request) == BINDER_SET_CONTEXT_MGR) {
            printf("binder_context_pid=%d\nbinder_context_ready=1\n", getpid());
            fflush(stdout);
        }
        return result;
    }
    return static_cast<int>(syscall(__NR_ioctl, fd, static_cast<unsigned int>(request), argument));
}

extern "C" int close(int fd) {
    if (!ready) return static_cast<int>(syscall(__NR_close, fd));
    if (!insideDriver() && a10binder_is_driver(fd)) {
        DriverCall call;
        return a10binder_close(fd);
    }
    return static_cast<int>(syscall(__NR_close, fd));
}

extern "C" void* mmap(void* address, size_t size, int protection, int flags, int fd, off_t offset) {
    if (!ready)
        return reinterpret_cast<void*>(syscall(__NR_mmap, address, size, protection, flags, fd, offset));
    // Anonymous allocator maps must never acquire the Binder metadata lock.
    if (fd >= 0 && !(flags & MAP_ANONYMOUS) && !insideDriver() && a10binder_is_driver(fd)) {
        if (offset || (protection & PROT_WRITE)) { errno = EINVAL; return MAP_FAILED; }
        protection = PROT_NONE;
        flags = (flags & ~MAP_SHARED) | MAP_PRIVATE | MAP_ANONYMOUS;
        fd = -1;
    }
    return reinterpret_cast<void*>(syscall(__NR_mmap, address, size, protection, flags, fd, offset));
}

extern "C" void* mmap64(void* address, size_t size, int protection, int flags, int fd, off64_t offset) {
    return mmap(address, size, protection, flags, fd, offset);
}

// Round 71: the frame-pointer chain printed by crashHandler cannot resolve this
// abort - its 8th frame is an address outside every mapping (a JIT address), so
// the reduction stops there. bionic's abort() is an ordinary exported symbol, so
// this preloaded library interposes on it and prints a real unwound backtrace at
// the exact abort site, then hands over to the original implementation. This
// only adds output; the exit status and the signal are unchanged.
extern "C" void abort() {
    // Round 72b: name the *caller*. bionic's abort() has no frame pointer, so the
    // unwound backtrace below only ever returns that single frame and the
    // calling library stays unknown. Inside this interposer
    // __builtin_return_address(0) is the address in whoever called abort()
    // (libbase's LogMessage, libaudioclient, libinputflinger, ...), which
    // formatAddress() turns into "<path>+<offset>" using /proc/self/maps.
    void* callerAddress = __builtin_return_address(0);
    char callerWhere[192];
    formatAddress(callerWhere, sizeof(callerWhere),
                  reinterpret_cast<uintptr_t>(callerAddress));
    char callerLine[256];
    int callerWritten = snprintf(callerLine, sizeof(callerLine),
                                 "A9VM abort caller=%s rawcaller=0x%lx\n", callerWhere,
                                 static_cast<unsigned long>(
                                     reinterpret_cast<uintptr_t>(callerAddress)));
    if (callerWritten > 0) write(2, callerLine, static_cast<size_t>(callerWritten));

    void* frames[48];
    int count = backtrace(frames, 48);
    char line[128];
    int written = snprintf(line, sizeof(line), "A9VM abort bt frames=%d\n", count);
    if (written > 0) write(2, line, static_cast<size_t>(written));
    for (int index = 0; index < count; ++index) {
        written = snprintf(line, sizeof(line), "A9VM abort frame %d = 0x%lx\n", index,
                           static_cast<unsigned long>(reinterpret_cast<uintptr_t>(frames[index])));
        if (written <= 0) break;
        write(2, line, static_cast<size_t>(written));
    }
    typedef void (*AbortFn)();
    AbortFn original = reinterpret_cast<AbortFn>(dlsym(RTLD_NEXT, "abort"));
    if (original != nullptr) original();
    __builtin_trap();
}

/*
 * dalvikvm64 has no android::AndroidRuntime instance: gCurRuntime is only created
 * by app_process. ActivityThread.main() calls
 * Process.setArgV0("<pre-initialized>"), and the image's native path
 * (android_os_Process_setArgV0 -> AndroidRuntime::setArgv0) loads gCurRuntime
 * without a null check and memsets the runtime's private mArgv0 buffer through
 * [this + 0x38]:
 *
 *   1cdeb8: ldr x0, [x8, #0x438]     ; gCurRuntime
 *   1cdebc: bl  AndroidRuntime::setArgv0(const char*, bool)
 *   115e58: ldp x0, x2, [x19, #0x38] ; this->mArgv0 / mArgv0Size
 *
 * With gCurRuntime == NULL that is a fault at address 0x38: the app process dies
 * with SIGSEGV (exit 139) right after TrustedCertificateStore loads
 * libjavacrypto and no Java exception or crash line can be printed (real device
 * 2026-10-29, instance 1793275717531, child 15055: log ends at
 * "Load libjavacrypto.so ... ok", ActivityThread.main never attaches).
 *
 * libandroid_runtime calls the symbol through its PLT, so this preloaded library
 * interposes it and implements the safe subset: the thread name (the only effect
 * a guest app observes) and setprogname(). The mArgv0 copy is only read by
 * AndroidRuntime::getArgv0(), which nothing in this runtime calls; the
 * alternative — faking a whole AndroidRuntime object — is undefined behaviour.
 */
extern "C" void a15vm_set_argv0(const char* argv0, bool setProcName)
        __asm__("_ZN7android14AndroidRuntime8setArgv0EPKcb");

extern "C" void setprogname(const char* progname);

extern "C" void a15vm_set_argv0(const char* argv0, bool setProcName) {
    static volatile int reported = 0;
    if (reported == 0) {
        reported = 1;
        write(2, "A9VM setArgv0=safe\n", 19);
    }
    if (argv0 == nullptr) return;
    if (setProcName) {
        size_t length = strlen(argv0);
        const char* name = length > 15 ? argv0 + (length - 15) : argv0;
        pthread_setname_np(pthread_self(), name);
    }
    setprogname(argv0);
}
