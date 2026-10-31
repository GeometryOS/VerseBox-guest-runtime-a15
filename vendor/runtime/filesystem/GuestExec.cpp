#include "GuestPaths.h"
#include <alloca.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static bool environmentHas(char* const env[], const char* key) {
    size_t length = strlen(key);
    for (size_t i = 0; env && env[i]; ++i) {
        if (!strncmp(env[i], key, length) && env[i][length] == '=') return true;
    }
    return false;
}

static bool skipDex2oat(const char* path) {
    if (!getenv("A10VM_SKIP_DEX2OAT")) return false;
    const char* base = strrchr(path, '/');
    base = base ? base + 1 : path;
    // API 28's installd always forks before exec, so exiting 0 here makes its
    // waitpid see a successful dex2oat child.  The ROM only ships an ELF32
    // dex2oat (AArch64 runtime cannot execute it), and the 64-bit stub in
    // /system/bin still has to go through the guest linker, which is why the
    // prepared-image replacement alone never cleared the 74 DexInv failures.
    return !strcmp(base, "dex2oat") || !strcmp(base, "dex2oatd");
}

static void traceExec(const char* path, bool skipped) {
    // stderr is useless for this probe: installd's dex2oat child redirects fd
    // 1/2 to liblogwrap's log pipe, so a write(2) marker never reaches
    // framework-installer.log.  Appending to $A10VM_STATE_ROOT/a9exec.log
    // survives that redirect and answers the only question that matters:
    // does installd's exec reach libc's execve wrapper at all?
    const char* state = getenv("A10VM_STATE_ROOT");
    if (!state) return;
    char log[PATH_MAX];
    size_t used = strlen(state);
    if (used + 12 >= sizeof(log)) return;
    memcpy(log, state, used);
    memcpy(log + used, "/a9exec.log", 12);
    int fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, log,
            O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600));
    if (fd < 0) return;
    char line[PATH_MAX + 16];
    size_t length = 0;
    if (skipped) { memcpy(line, "skip ", 5); length = 5; }
    else { memcpy(line, "enter ", 6); length = 6; }
    size_t pathLength = strlen(path);
    if (pathLength > PATH_MAX) pathLength = PATH_MAX;
    memcpy(line + length, path, pathLength);
    length += pathLength;
    line[length++] = '\n';
    syscall(__NR_write, fd, line, length);
    syscall(__NR_close, fd);
}

/**
 * Makes sure the file the guest is about to execute has an execute bit.
 *
 * <p>为什么必须在运行时补：客体 rootfs 里的文件是从 APK assets 解出来的，权限来自
 * prepared 清单的 mode 字段，而这份清单在 **Windows** 上生成时拿不到 POSIX 模式，
 * 于是 apex/bin 下的二进制一律记成 0666（extract-apex.py 的 fallback）。内核拒绝
 * exec 一个没有 x 位的文件 → artd 每条 dexopt 都在这里失败：
 *
 * <pre>
 *   Failed to execute (/apex/com.android.art/bin/art_exec --drop-capabilities ...):
 *     Permission denied            (vm/framework-artd.log, 实例 1793447452431)
 *   dex2oat returned an unexpected code: 134
 * </pre>
 *
 * <p>也就是说“安装/清数据/卸载卡住、应用存储占用一直转圈”的那串现象，源头是这一个
 * 权限位。文件属于本进程的 uid（整个实例都在 app 沙箱里），所以 chmod 一定成功；
 * 只在 X_OK 真的失败、且 errno 是 EACCES 时才补，不影响正常路径。
 */
static int ensureExecutable(const char* path) {
    if (syscall(__NR_faccessat, AT_FDCWD, path, X_OK) == 0) return 0;
    int saved = errno;
    if (saved != EACCES) return -1;
    // 跟随符号链接（/apex/com.android.art/bin/dex2oat64 -> liba15vm-dex2oat64.so）。
    if (syscall(__NR_fchmodat, AT_FDCWD, path, 0755) < 0) { errno = saved; return -1; }
    if (syscall(__NR_faccessat, AT_FDCWD, path, X_OK) < 0) return -1;
    return 0;
}

static int guestExec(const char* path, char* const argv[], char* const env[], unsigned depth) {
    if (depth > 4) { errno = ELOOP; return -1; }
    bool skipped = skipDex2oat(path);
    traceExec(path, skipped);
    if (skipped) _exit(0);
    char buffer[PATH_MAX];
    const char* mapped = guestPath(AT_FDCWD, path, buffer);
    if (!mapped) return -1;
    if (mapped == path || !guestRoot()) return syscall(__NR_execve, path, argv, env);
    int fd = syscall(__NR_openat, AT_FDCWD, mapped, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    char prefix[257]{};
    ssize_t size = TEMP_FAILURE_RETRY(read(fd, prefix, sizeof(prefix) - 1));
    int saved = errno;
    close(fd);
    if (size < 0) { errno = saved; return -1; }
    if (ensureExecutable(mapped) < 0) return -1;
    size_t argc = 0, envc = 0;
    while (argv && argv[argc]) if (++argc > 131072) { errno = E2BIG; return -1; }
    while (env && env[envc]) if (++envc > 131072) { errno = E2BIG; return -1; }
    if (argc + envc > 131072) { errno = E2BIG; return -1; }
    if (size >= 2 && prefix[0] == '#' && prefix[1] == '!') {
        char* end = strchr(prefix, '\n');
        if (!end) {
            if (size == sizeof(prefix) - 1) { errno = ENOEXEC; return -1; }
            end = prefix + size;
        }
        *end = '\0';
        while (end > prefix + 2 && (end[-1] == ' ' || end[-1] == '\t')) *--end = '\0';
        char* interpreter = prefix + 2;
        while (*interpreter == ' ' || *interpreter == '\t') ++interpreter;
        if (!*interpreter) { errno = ENOEXEC; return -1; }
        char* option = interpreter;
        while (*option && *option != ' ' && *option != '\t') ++option;
        if (*option) {
            *option++ = '\0';
            while (*option == ' ' || *option == '\t') ++option;
        }
        char** arguments = static_cast<char**>(alloca((argc + 4) * sizeof(char*)));
        size_t count = 0;
        arguments[count++] = interpreter;
        if (*option) arguments[count++] = option;
        arguments[count++] = const_cast<char*>(path);
        for (size_t i = 1; i < argc; ++i) arguments[count++] = argv[i];
        arguments[count] = nullptr;
        return guestExec(interpreter, arguments, env, depth + 1);
    }
    Elf64_Ehdr header;
    if (size < sizeof(header)) { errno = ENOEXEC; return -1; }
    memcpy(&header, prefix, sizeof(header));
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) ||
        header.e_ident[EI_CLASS] != ELFCLASS64 || header.e_ident[EI_DATA] != ELFDATA2LSB ||
        header.e_machine != EM_AARCH64 || header.e_type != ET_DYN) {
        errno = ENOEXEC;
        return -1;
    }
    const char* loader = getenv("A10VM_EXEC_LINKER");
    if (!loader || loader[0] != '/') { errno = EINVAL; return -1; }
    char** arguments = static_cast<char**>(alloca((argc + 3) * sizeof(char*)));
    arguments[0] = const_cast<char*>(loader);
    arguments[1] = const_cast<char*>(mapped);
    size_t count = 2;
    for (size_t i = 1; i < argc; ++i) arguments[count++] = argv[i];
    arguments[count] = nullptr;
    static const char* keys[] = {"A10VM_ROOTFS", "A10VM_HOST_ROOT", "A10VM_STATE_ROOT",
        "A10VM_EXEC_LINKER", "A10VM_PROPERTIES", "A10VM_LD_CONFIG"};
    char** environment = static_cast<char**>(alloca((envc + 8) * sizeof(char*)));
    count = 0;
    for (size_t i = 0; i < envc; ++i) {
        if (strncmp(env[i], "A10VM_EXEC_ARGV0=", 17)) environment[count++] = env[i];
    }
    char settings[8 * PATH_MAX];
    size_t used = 0;
    for (const char* key : keys) {
        const char* value = getenv(key);
        if (!value || environmentHas(env, key)) continue;
        size_t length = strlen(key) + strlen(value) + 2;
        if (length > sizeof(settings) - used) { errno = E2BIG; return -1; }
        char* entry = settings + used;
        strcpy(entry, key);
        strcat(entry, "=");
        strcat(entry, value);
        environment[count++] = entry;
        used += length;
    }
    const char* argv0 = argc ? argv[0] : path;
    size_t length = strlen(argv0) + sizeof("A10VM_EXEC_ARGV0=");
    if (length > sizeof(settings) - used) { errno = E2BIG; return -1; }
    char* entry = settings + used;
    strcpy(entry, "A10VM_EXEC_ARGV0=");
    strcat(entry, argv0);
    environment[count++] = entry;
    environment[count] = nullptr;
    return syscall(__NR_execve, loader, arguments, environment);
}

int execve(const char* path, char* const argv[], char* const env[]) {
    return guestExec(path, argv, env, 0);
}
