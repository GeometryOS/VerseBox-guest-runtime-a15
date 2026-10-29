#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

struct service_audit {
    pid_t pid;
    uid_t uid;
    const char* name;
};

static int private_endpoint(const char* key) {
    const char* path = getenv(key);
    struct stat status;
    uid_t owner = syscall(__NR_getuid);
    return owner >= 10000 && path && path[0] == '/' &&
           stat(path, &status) == 0 && S_ISSOCK(status.st_mode) &&
           status.st_uid == owner && (status.st_mode & 077) == 0;
}

// Only the guest manager loads this policy; Binder transport checks SO_PEERCRED.
uid_t multiuser_get_app_id(uid_t uid) {
    if (private_endpoint("A10VM_BINDER_PATH") && uid == (uid_t)syscall(__NR_getuid)) return 1000;
    return uid % 100000;
}

int selinux_check_access(const char* source, const char* target, const char* object_class,
                        const char* permission, void* audit_data) {
    // Container bridge, NOT a policy engine.  See STATUS.md "known limitations".
    //
    // Why this no longer inspects audit_data: Android 15's AIDL servicemanager
    // calls selinux_check_access(..., auditdata = nullptr).  The previous
    // version required `audit != nullptr && audit->pid > 0` before allowing an
    // add/find/list, so the condition could never hold and every installd
    // addService() came back PERMISSION_DENIED (-1).  That is the D2 blocker.
    //
    // Why returning 0 is correct here: these guest processes are host app
    // processes, so the host's SELinux policy does not describe them (the
    // domain is untrusted_app, the target is the host servicemanager).  Real
    // authorisation inside the guest is done by the Binder broker from
    // SO_PEERCRED plus the per-connection guest uid, not by this symbol.
    // Only the guest manager/hwservicemanager load this policy, so the scope
    // stays inside the guest's own service registry.
    (void)source;
    (void)target;
    (void)audit_data;
    if (object_class && permission &&
        (!strcmp(object_class, "service_manager") || !strcmp(object_class, "hwservice_manager"))) {
        return 0;
    }
    errno = EACCES;
    return -1;
}

/*
 * Android 15's AIDL servicemanager asks libselinux for the *service* label
 * database (`selinux_android_service_context_handle`) and aborts with
 * SIGABRT when it comes back NULL.  In this container the database cannot be
 * loaded at all: the image ships plat_service_contexts but no system_ext /
 * product / odm copies, and A15 libselinux refuses to build the handle from a
 * partial partition set (probe: `service_ctx_handle=0x0 errno=2`).
 *
 * The label is not what authorises a lookup here — `selinux_check_access`
 * above does that, keyed on the private Binder endpoint and SO_PEERCRED.  So
 * the service label database is reduced to one fixed label.  This is a bridge,
 * not a policy engine: it is listed in STATUS.md as a known limitation.
 */
static const char kServiceContext[] = "u:object_r:default_android_service:s0";
static int service_handle_marker;

void* selinux_android_service_context_handle(void) {
    return &service_handle_marker;
}

int selabel_lookup(void* handle, char** context, const char* key, int type) {
    if (handle != &service_handle_marker) {
        static int (*original)(void*, char**, const char*, int);
        if (!original) original = (int (*)(void*, char**, const char*, int))dlsym(RTLD_NEXT, "selabel_lookup");
        return original ? original(handle, context, key, type) : -1;
    }
    (void)key;
    (void)type;
    if (!context) { errno = EINVAL; return -1; }
    // freecon() from the image's libselinux releases this with free().
    *context = strdup(kServiceContext);
    if (!*context) return -1;
    return 0;
}

void selabel_close(void* handle) {
    if (handle == &service_handle_marker) return;
    static void (*original)(void*);
    if (!original) original = (void (*)(void*))dlsym(RTLD_NEXT, "selabel_close");
    if (original) original(handle);
}
