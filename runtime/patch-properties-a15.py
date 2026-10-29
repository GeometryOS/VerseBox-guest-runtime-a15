#!/usr/bin/env python3
"""Apply the container property-area contract to the derived A15 sources.

The A9 `.patch` files no longer apply to Android 15: `SystemProperties` grew an
`appcompat_override` context set, `InitContexts(bool)` was split out, and the
filenames became `PropertiesFilename` objects. Rather than rebasing a fragile
unified diff, this script performs the same edits with literal anchors and fails
when an anchor is missing.

Contract:
  1. `SystemProperties` must stay trivially constructible (A15 static_assert);
     zero-initialisation comes from static storage, so the default constructor
     is left untouched.
  2. `Init()` re-initialises when the requested directory changes, and
     `Reset()` frees the previous mapping.
  3. `InitContexts()` always selects `ContextsSerialized`; the private property
     directory has no `/dev/__properties__/property_info`.
  4. `prop_area` does not set a SELinux xattr, and accepts property files owned
     by the current euid/egid with the exact private size.
  5. `PropertyInfoAreaFile::LoadPath()` accepts the same ownership.
"""
import pathlib
import sys

HEADER_EDITS = [
    (
        """  bool InitContexts(bool load_default_path);
""",
        """  bool InitContexts(bool load_default_path);
  void Reset();
""",
    ),
]

CPP_EDITS = [
    (
        """bool SystemProperties::Init(const char* filename) {""",
        """void SystemProperties::Reset() {
  if (contexts_ != nullptr) {
    contexts_->FreeAndUnmap();
    contexts_->~Contexts();
  }
  contexts_ = nullptr;
  if (appcompat_override_contexts_ != nullptr) {
    appcompat_override_contexts_->FreeAndUnmap();
    appcompat_override_contexts_->~Contexts();
  }
  appcompat_override_contexts_ = nullptr;
  initialized_ = false;
}

bool SystemProperties::Init(const char* filename) {""",
    ),
    (
        """  if (initialized_) {
    contexts_->ResetAccess();
    return true;
  }""",
        """  if (initialized_) {
    if (strcmp(properties_filename_.c_str(), filename) != 0) {
      Reset();
    } else {
      contexts_->ResetAccess();
      return true;
    }
  }""",
    ),
    (
        """bool SystemProperties::InitContexts(bool load_default_path) {
  if (is_dir(properties_filename_.c_str())) {
    if (access(PROP_TREE_FILE, R_OK) == 0) {
      auto serial_contexts = new (contexts_data_) ContextsSerialized();
      contexts_ = serial_contexts;
      if (!serial_contexts->Initialize(false, properties_filename_.c_str(), nullptr,
                                       load_default_path)) {
        return false;
      }
    } else {
      contexts_ = new (contexts_data_) ContextsSplit();
      if (!contexts_->Initialize(false, properties_filename_.c_str(), nullptr)) {
        return false;
      }
    }
  } else {
    contexts_ = new (contexts_data_) ContextsPreSplit();
    if (!contexts_->Initialize(false, properties_filename_.c_str(), nullptr)) {
      return false;
    }
  }
  return true;
}""",
        """bool SystemProperties::InitContexts(bool load_default_path) {
  // The private property area is a plain directory under the app state root and
  // has no /dev/__properties__/property_info. ContextsSerialized already reads
  // "<dirname>/property_info" when load_default_path is false, so skip the
  // PROP_TREE_FILE probe instead of falling back to ContextsSplit.
  (void)load_default_path;
  if (!is_dir(properties_filename_.c_str())) return false;
  auto serial_contexts = new (contexts_data_) ContextsSerialized();
  contexts_ = serial_contexts;
  if (!serial_contexts->Initialize(false, properties_filename_.c_str(), nullptr, false)) {
    Reset();
    return false;
  }
  return true;
}""",
    ),
]

# The A9 runtime.patch's other half: `__system_properties_init()` must open the
# private property area named by A10VM_PROPERTIES. Android 15's upstream file
# still hard-codes PROP_DIRNAME ("/dev/__properties__"), which does not exist
# inside the app sandbox. Without this edit every guest process reads an empty
# property area, and A15's AndroidRuntime stops at startVm:
#   "ro.dalvik.vm.native.bridge is not expected to be empty"
#   "ro.product.cpu.abilist64 is not expected to be empty"
# then app_process exits before desktop_pid is ever printed (real-device log
# 2026-10-27, instance 1793083694069, framework-server.log). Keep the A9
# contract: absolute A10VM_PROPERTIES only, never fall back to the host area.
API_EDITS = [
    (
        "#include <sys/system_properties.h>\n",
        "#include <sys/system_properties.h>\n#include <stdlib.h>\n",
    ),
    (
        """__BIONIC_WEAK_FOR_NATIVE_BRIDGE
int __system_properties_init() {
  return system_properties.Init(PROP_DIRNAME) ? 0 : -1;
}""",
        """// The private property area is a directory under the app state root; there
// is no /dev/__properties__ inside the app sandbox. An absolute
// A10VM_PROPERTIES is the only accepted source (same contract as A9).
static const char* private_property_path() {
  const char* path = getenv("A10VM_PROPERTIES");
  return path != nullptr && path[0] == '/' ? path : nullptr;
}

__BIONIC_WEAK_FOR_NATIVE_BRIDGE
int __system_properties_init() {
  const char* path = private_property_path();
  return path != nullptr && system_properties.Init(path) ? 0 : -1;
}""",
    ),
    (
        """__BIONIC_WEAK_FOR_NATIVE_BRIDGE
int __system_property_area_init() {
  bool fsetxattr_fail = false;
  return system_properties.AreaInit(PROP_DIRNAME, &fsetxattr_fail) && !fsetxattr_fail ? 0 : -1;
}""",
        """__BIONIC_WEAK_FOR_NATIVE_BRIDGE
int __system_property_area_init() {
  bool fsetxattr_fail = false;
  const char* path = private_property_path();
  return path != nullptr && system_properties.AreaInit(path, &fsetxattr_fail) &&
         !fsetxattr_fail ? 0 : -1;
}""",
    ),
]

PROP_AREA_EDITS = [
    (
        """  if (context) {
    if (fsetxattr(fd, XATTR_NAME_SELINUX, context, strlen(context) + 1, 0) != 0) {
      async_safe_format_log(ANDROID_LOG_ERROR, "libc",
                            "fsetxattr failed to set context (%s) for \\"%s\\"", context, filename);
      /*
       * fsetxattr() will fail during system properties tests due to selinux policy.
       * We do not want to create a custom policy for the tester, so we will continue in
       * this function but set a flag that an error has occurred.
       * Init, which is the only daemon that should ever call this function will abort
       * when this error occurs.
       * Otherwise, the tester will ignore it and continue, albeit without any selinux
       * property separation.
       */
      if (fsetxattr_failed) {
        *fsetxattr_failed = true;
      }
    }
  }""",
        """  // Instance files retain the application's natural SELinux label.
  (void)context;
  if (fsetxattr_failed) *fsetxattr_failed = false;""",
    ),
    (
        """  if ((fd_stat.st_uid != 0) || (fd_stat.st_gid != 0) ||
      ((fd_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) ||
      (fd_stat.st_size < static_cast<off_t>(sizeof(prop_area)))) {
    return nullptr;
  }""",
        """  if ((fd_stat.st_uid != static_cast<uid_t>(syscall(__NR_geteuid))) ||
      (fd_stat.st_gid != static_cast<gid_t>(syscall(__NR_getegid))) ||
      !S_ISREG(fd_stat.st_mode) ||
      ((fd_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) ||
      (fd_stat.st_size != static_cast<off_t>(PA_SIZE))) {
    return nullptr;
  }""",
    ),
    (
        "#include <sys/types.h>\n#include <sys/xattr.h>\n#include <unistd.h>\n",
        "#include <sys/types.h>\n#include <sys/xattr.h>\n#include <sys/syscall.h>\n#include <unistd.h>\n",
    ),
]

PARSER_EDITS = [
    (
        """  if ((fd_stat.st_uid != 0) || (fd_stat.st_gid != 0) ||
      ((fd_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) ||
      (fd_stat.st_size < static_cast<off_t>(sizeof(PropertyInfoArea)))) {""",
        """  if ((fd_stat.st_uid != static_cast<uid_t>(syscall(__NR_geteuid))) ||
      (fd_stat.st_gid != static_cast<gid_t>(syscall(__NR_getegid))) ||
      !S_ISREG(fd_stat.st_mode) ||
      ((fd_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) ||
      (fd_stat.st_size < static_cast<off_t>(sizeof(PropertyInfoArea)))) {""",
    ),
    (
        "#include <sys/types.h>\n#include <unistd.h>\n",
        "#include <sys/types.h>\n#include <sys/syscall.h>\n#include <unistd.h>\n",
    ),
]

# Android 15 builds libc with libc++ 19, whose atomic_store_explicit accepts a
# value convertible to the atomic's value type. The bridges here compile with
# the source libc++ headers (the image's libc++.so ABI namespace is __1), where
# the value must match exactly; `sizeof(...) - 1` is size_t, so narrow it
# explicitly. Same result, no ABI change.
PROP_INFO_EDITS = [
    (
        "atomic_store_explicit(&this->serial, error_value_len << 24 | kLongFlag, memory_order_relaxed);",
        "atomic_store_explicit(&this->serial,\n"
        "                        static_cast<uint32_t>(error_value_len << 24 | kLongFlag),\n"
        "                        memory_order_relaxed);",
    ),
]

# Same narrowing for the serial update: A15 declares it `int`, while the
# serial is atomic_uint_least32_t. With libc++ 19 the conversion is implicit;
# the source-tree libc++ here requires the exact type.
SERIAL_EDITS = [
    (
        "  int new_serial = (len << 24) | ((serial + 1) & 0xffffff);",
        "  uint32_t new_serial = (len << 24) | ((serial + 1) & 0xffffff);",
    ),
]


def patch(path, edits):
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in edits:
        count = text.count(old)
        if count != 1:
            raise SystemExit("patch-properties-a15: anchor matched %d times in %s\n--- anchor ---\n%s"
                             % (count, path, old[:300]))
        text = text.replace(old, new, 1)
    if text == original:
        raise SystemExit("patch-properties-a15: %s unchanged" % path)
    path.write_text(text, encoding="utf-8")
    print("patched: " + str(path))


def main():
    derived = pathlib.Path(sys.argv[1])
    patch(derived / "bionic/libc/system_properties/include/system_properties/system_properties.h",
          HEADER_EDITS)
    patch(derived / "bionic/libc/bionic/system_property_api.cpp", API_EDITS)
    patch(derived / "bionic/libc/system_properties/system_properties.cpp", CPP_EDITS)
    patch(derived / "bionic/libc/system_properties/system_properties.cpp", SERIAL_EDITS)
    patch(derived / "bionic/libc/system_properties/prop_area.cpp", PROP_AREA_EDITS)
    patch(derived / "bionic/libc/system_properties/prop_info.cpp", PROP_INFO_EDITS)
    patch(derived / "core/property_service/libpropertyinfoparser/property_info_parser.cpp",
          PARSER_EDITS)


if __name__ == "__main__":
    main()
