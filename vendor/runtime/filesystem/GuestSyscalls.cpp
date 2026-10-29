#include "GuestPaths.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" long a10vm_kernel_syscall(long, long, long, long, long, long, long);
extern "C" int __openat(int, const char*, int, int);

extern "C" long a10vm_path_syscall(long number, long a, long b, long c, long d, long e, long f) {
    if (!guestRoot()) return a10vm_kernel_syscall(number, a, b, c, d, e, f);
    if (number == __NR_openat)
        return __openat(a, reinterpret_cast<const char*>(b), c, d);
    if (number == __NR_readlinkat)
        return readlinkat(a, reinterpret_cast<const char*>(b), reinterpret_cast<char*>(c), d);
    if (number == __NR_symlinkat) {
        char destination[PATH_MAX];
        const char* mapped = guestPath(b, reinterpret_cast<const char*>(c), destination, false);
        return mapped ? a10vm_kernel_syscall(number, a, b, reinterpret_cast<long>(mapped), d, e, f) : -1;
    }
    char first[PATH_MAX], second[PATH_MAX];
    long* path = &b;
    int directory = a;
    bool follow = true;
    switch (number) {
        case __NR_newfstatat: follow = !(d & AT_SYMLINK_NOFOLLOW); break;
        case __NR_utimensat:
            if (!b) return a10vm_kernel_syscall(number, a, b, c, d, e, f);
            follow = !(d & AT_SYMLINK_NOFOLLOW);
            break;
        case __NR_mkdirat:
        case __NR_unlinkat:
        case __NR_readlinkat:
        case __NR_renameat:
        case __NR_renameat2: follow = false; break;
        case __NR_statfs:
        case __NR_truncate:
        case __NR_chdir: path = &a; directory = AT_FDCWD; break;
        case __NR_inotify_add_watch: directory = AT_FDCWD; break;
    }
    const char* mapped = number == __NR_mkdirat
        ? guestMkdirPath(directory, reinterpret_cast<const char*>(*path), first)
        : guestPath(directory, reinterpret_cast<const char*>(*path), first, follow);
    if (!mapped) return -1;
    *path = reinterpret_cast<long>(mapped);
    if (number == __NR_renameat || number == __NR_renameat2) {
        mapped = guestPath(c, reinterpret_cast<const char*>(d), second, false);
        if (!mapped) return -1;
        d = reinterpret_cast<long>(mapped);
    }
    return a10vm_kernel_syscall(number, a, b, c, d, e, f);
}

// ---------------------------------------------------------------------------
// Round 76e: the container has no kernel BPF, and libnetworkstats is native
// ---------------------------------------------------------------------------
// A15BpfMaps replaces the Java BpfMap objects, but NetworkStatsService's native
// reader (apex/com.android.tethering/lib64/libnetworkstats.so) calls bpf(2)
// directly. Its map-info helper aborts on any error other than EINTR
// (libnetworkstats.so+0x237bc sets errno 95 and calls abort), and EACCES is what
// this SELinux domain gets for /sys/fs/bpf/netd_shared:
//
//   NetworkStats: Successfully imported platform collections
//   BestClock: java.time.DateTimeException: Missing network time fix
//   A9VM abort caller=liba15vm-image-f0851e5c15136be6ba73.so+0x2376c
//   A9VM bdev crash sig=6 ... (instance 1793228200547, 2026-10-29 06:57)
//
// Disassembly of the abort site (llvm-objdump, libnetworkstats.so):
//   0x23624 bl syscall@plt        // bpf(BPF_OBJ_GET_INFO_BY_FD, ...)
//   0x23638 tbnz w0,#0x1f, 0x237ac  // negative -> errno 95 + abort
//   0x23794 bl __stack_chk_fail
//   0x23798/0x237bc bl abort
//
// The only way to keep NetworkStatsService.systemReady() alive is to answer the
// two object-info queries exactly like an empty map: type HASH, key_size 4,
// value_size 32 (the reader requires > 23), max_entries 0. Lookups then report
// ENOENT ("no entry") and updates are accepted and discarded, the same semantics
// A15BpfMaps gives the Java maps. Only SYS_bpf is intercepted here; every other
// syscall keeps the normal path through a10vm_kernel_syscall.
namespace {

// union bpf_attr's "info" member is the first union alternative: offset 0 holds
// bpf_fd, 4 holds info_len, 8 holds the user pointer.
struct A15BpfInfoArgs {
    uint32_t bpf_fd;
    uint32_t info_len;
    uint64_t info;
} __attribute__((packed));

// struct bpf_map_info (kernel uapi), the fields libnetworkstats reads back:
// type at 0, id at 4, key_size at 8, value_size at 12, max_entries at 16.
struct A15BpfMapInfo {
    uint32_t type;
    uint32_t id;
    uint32_t key_size;
    uint32_t value_size;
    uint32_t max_entries;
    uint32_t map_flags;
    char name[16];
    uint32_t ifindex;
    uint32_t btf_vmlinux_value_type_id;
    uint64_t netns_dev;
    uint64_t netns_ino;
    uint32_t btf_id;
    uint32_t btf_key_type_id;
    uint32_t btf_value_type_id;
    uint32_t btf_vmlinux_id;
    uint64_t map_extra;
} __attribute__((packed));

}  // namespace

// The container has no logd reader, so the observation goes to fd 2, which the
// session already captures as framework-server.log (same path as the guest-log
// bridge's FATAL mirror). Bounded so a stats loop cannot flood the file.
static int a15_bpf_reported;

static void a15_bpf_report(long cmd, long result, long fd) {
    if (a15_bpf_reported >= 48) return;
    ++a15_bpf_reported;
    char buffer[96];
    int length = snprintf(buffer, sizeof(buffer),
                          "a15_bpf cmd=%ld ret=%ld fd=%ld\n", cmd, result, fd);
    if (length > 0) write(2, buffer, static_cast<size_t>(length));
}

extern "C" long a10vm_bpf_syscall(long number, long cmd, long attribute,
                                  long, long, long, long) {
    (void)number;
    auto* args = reinterpret_cast<A15BpfInfoArgs*>(attribute);
    if (args == nullptr) {
        a15_bpf_report(cmd, -1, -1);
        errno = EFAULT;
        return -1;
    }
    switch (cmd) {
        case 15: {  // BPF_OBJ_GET_INFO_BY_FD
            A15BpfMapInfo info{};
            info.type = 1;          // BPF_MAP_TYPE_HASH
            info.id = 1;
            info.key_size = 4;
            info.value_size = 32;   // libnetworkstats requires > 23
            info.max_entries = 1024;
            if (args->info == 0) {
                a15_bpf_report(cmd, -1, args->bpf_fd);
                errno = EFAULT;
                return -1;
            }
            uint32_t length = args->info_len;
            if (length > sizeof(info)) length = sizeof(info);
            memcpy(reinterpret_cast<void*>(args->info), &info, length);
            args->info_len = static_cast<uint32_t>(sizeof(info));
            a15_bpf_report(cmd, 0, args->bpf_fd);
            return 0;
        }
        case 1:  // BPF_MAP_LOOKUP_ELEM
        case 4:  // BPF_MAP_GET_NEXT_KEY
            a15_bpf_report(cmd, -1, args->bpf_fd);
            errno = ENOENT;
            return -1;
        case 2:  // BPF_MAP_UPDATE_ELEM
        case 3:  // BPF_MAP_DELETE_ELEM
            a15_bpf_report(cmd, 0, args->bpf_fd);
            return 0;
        default:
            a15_bpf_report(cmd, -1, args->bpf_fd);
            errno = EOPNOTSUPP;
            return -1;
    }
}
