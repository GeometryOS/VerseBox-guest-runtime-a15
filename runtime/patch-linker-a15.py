#!/usr/bin/env python3
"""Derive the API 35 private linker sources with the project's container contract.

Android 15's linker changed relative to A9:

  * `get_ld_config_file_path()` now takes the executable path and consults the
    APEX/generated configurations before anything else;
  * the default namespace has two flags (`g_is_asan`, `g_is_hwasan`);
  * `Config::read_binary_config()` still matches `dir.<section>` against the
    physical binary path, but the helper is called with `resolved_path` from
    `linker_config.cpp`.

What the project needs is the same three properties A9/A10 established:

  1. `get_ld_config_file_path()` reads `A10VM_LD_CONFIG` and nothing else;
  2. a rejected configuration is fatal instead of silently falling back;
  3. namespace paths written in host coordinates are resolved in guest
     coordinates, and section matching compares physical inodes.

A15 already supports direct execution (`linker64 /path/program`), so the A9
`load_executable()` port is NOT needed here. Do not copy that patch.

Every anchor is literal upstream text and a missing anchor is a hard error: a
linker that silently keeps its own config lookup looks like "library not found"
on the device and costs a full debugging round.
"""
import pathlib

RT = pathlib.Path(__file__).resolve().parent
UP = RT / "upstream" / "bionic" / "linker"
OUT = RT / "build" / "derived" / "bionic" / "linker"

SOURCES = ("linker.cpp", "linker_config.cpp", "linker_utils.cpp", "linker_soinfo.cpp",
           "linker_phdr.cpp", "linker_main.cpp")

LINKER_CPP = [
    (
        '#include "linker.h"\n',
        '#include "linker.h"\n#include "LinkerFileStat.h"\n',
    ),
    (
        """static bool file_exists(const char* path) {
  struct stat s;

  if (stat(path, &s) != 0) {""",
        """static bool file_exists(const char* path) {
  struct stat s;

  if (linkerPathStat(path, &s) != 0) {""",
    ),
    (
        """static std::string get_ld_config_file_path(const char* executable_path) {
#ifdef USE_LD_CONFIG_FILE
  // This is a debugging/testing only feature. Must not be available on
  // production builds.
  const char* ld_config_file_env = getenv("LD_CONFIG_FILE");
  if (ld_config_file_env != nullptr && file_exists(ld_config_file_env)) {
    return ld_config_file_env;
  }
#endif

  std::string path = get_ld_config_file_apex_path(executable_path);
  if (!path.empty()) {
    if (file_exists(path.c_str())) {
      return path;
    }
    DL_WARN("Warning: couldn't read config file \\"%s\\" for \\"%s\\"",
            path.c_str(), executable_path);
  }

  path = kLdConfigArchFilePath;
  if (file_exists(path.c_str())) {
    return path;
  }

  if (file_exists(kLdGeneratedConfigFilePath)) {
    return kLdGeneratedConfigFilePath;
  }

  if (is_linker_config_expected(executable_path)) {
    DL_WARN("Warning: failed to find generated linker configuration from \\"%s\\"",
            kLdGeneratedConfigFilePath);
  }

  path = get_ld_config_file_vndk_path();
  if (file_exists(path.c_str())) {
    return path;
  }

  return kLdConfigFilePath;
}""",
        """static std::string get_ld_config_file_path(const char* executable_path) {
  // Android 15's private linker contract: only A10VM_LD_CONFIG is consulted.
  // The guest runs every executable below /data/app (a host path), so the
  // APEX/generated/vndk configurations can never describe this runtime.
  (void)executable_path;
  const char* private_config = getenv("A10VM_LD_CONFIG");
  if (private_config != nullptr && private_config[0] == '/' && file_exists(private_config)) {
    return private_config;
  }
  async_safe_fatal("A10VM_LD_CONFIG requires an existing absolute private configuration");
}""",
    ),
    (
        """  if (config == nullptr) {
    return init_default_namespace_no_config(g_is_asan, g_is_hwasan);
  }""",
        """  if (config == nullptr) {
    async_safe_fatal("A10VM private linker configuration was rejected: "
                     "executable=%s reason=no matching executable section",
                     executable_path);
  }""",
    ),
    # The pinned libc++ tree predates C++20 unordered_map::contains(). The
    # upstream linker uses it in five places; find()/end() is the same test and
    # keeps the tree buildable without a newer libc++.
    (
        """    if (visited.contains(si)) {""",
        """    if (visited.find(si) != visited.end()) {""",
    ),
    (
        """!new_global_group_members.contains(si)""",
        """new_global_group_members.find(si) == new_global_group_members.end()""",
    ),
    (
        """    if (local_unload_list.contains(si)) {""",
        """    if (local_unload_list.find(si) != local_unload_list.end()) {""",
    ),
    (
        """        if (local_unload_list.contains(child)) {""",
        """        if (local_unload_list.find(child) != local_unload_list.end()) {""",
    ),
    (
        """    if (namespaces.contains(ns_config->name())) {""",
        """    if (namespaces.find(ns_config->name()) != namespaces.end()) {""",
    ),
    # A10VM guest patch: a dlopen-created namespace must see the linker itself.
    #
    # init_default_namespaces() adds the linker (ld-android.so) to every
    # namespace that comes from ld.config.txt. Namespaces created at runtime by
    # libnativeloader (android_create_namespace -> create_namespace, dlfcn.cpp)
    # only copy the parent's shared group, and solinker is not in that group, so
    # a bare "ld-android.so" request inside such a namespace misses the soname
    # search and the loader maps a SECOND copy of the 5.9 MB linker from the
    # instance's lib dir.
    #
    # The second copy never runs __linker_init(): its libc_shared_globals are
    # all zero, so its priority-1 constructor detect_self_exec() sees
    # auxv == nullptr and calls __linker_error("error: linker cannot load
    # itself"). Formatting that message reaches memcpy, whose lazy ifunc
    # dispatch (__bionic_call_ifunc_resolver -> getauxval) dereferences the same
    # null auxv. Real device 2026-10-28 (instance 1793174584698, pid 9420):
    #
    #   pc = linker+0x61370  getauxval+0x18: ldr x9,[x8]   x8 = auxv = 0
    #   bt: __bionic_call_ifunc_resolver+0x40 <- memcpy+0x68 <- out_vformat
    #       <- async_safe_format_log_va_list <- linker_log_va_list
    #       <- __linker_error
    #
    # and the same-run /proc/<pid>/maps snapshot taken ~0.2 s earlier has no
    # mapping at the crashed copy's base (0x6e8381b000) -> the copy is created
    # by the failing dlopen. Adding the linker here is exactly what
    # init_default_namespaces() already does for ld.config namespaces.
    (
        """  } else {
    // If not shared - copy only the shared group
    add_soinfos_to_namespace(parent_namespace->get_shared_group(), ns);
  }
""",
        """  } else {
    // If not shared - copy only the shared group
    add_soinfos_to_namespace(parent_namespace->get_shared_group(), ns);
  }

  // A10VM guest patch: keep ld-android.so resolvable in runtime-created
  // namespaces. Without this, a library that NEEDs ld-android.so (libdl.so
  // does) makes the loader map a second copy of the linker whose __linker_init
  // never runs; its detect_self_exec() then reports through the lazy memcpy
  // ifunc, which reads the null auxv of that copy and segfaults in getauxval.
  // init_default_namespaces() already adds the linker to every ld.config
  // namespace; runtime namespaces need the same treatment.
  if (ns != &g_default_namespace) {
    soinfo* ld_android_so = solist_get_head();
    if (ld_android_so != nullptr) ns->add_soinfo(ld_android_so);
  }
""",
    ),
]

CONFIG_MATCHER = """static bool config_matches_binary(const char* binary_path, const std::string& directory) {
  if (file_is_under_dir(binary_path, directory)) return true;
  // Execute-only install directories and bind aliases may retain different path names.
  struct stat expected;
  if (linkerPathStat(directory.c_str(), &expected) != 0 || !S_ISDIR(expected.st_mode)) return false;
  std::string parent = dirname(binary_path);
  while (parent != "/" && parent != ".") {
    struct stat actual;
    if (linkerPathStat(parent.c_str(), &actual) == 0 &&
        actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino) return true;
    std::string next = dirname(parent.c_str());
    if (next == parent) break;
    parent = std::move(next);
  }
  return false;
}

"""

LINKER_CONFIG_CPP = [
    (
        '#include "linker_utils.h"\n',
        '#include "linker_utils.h"\n'
        '#include "GuestPaths.h"\n'
        '#include "LinkerFileStat.h"\n'
        "#include <fcntl.h>\n"
        "#include <sys/stat.h>\n",
    ),
    (
        "static bool parse_config_file(const char* ld_config_file_path,",
        CONFIG_MATCHER + "static bool parse_config_file(const char* ld_config_file_path,",
    ),
    (
        """  ConfigParser cp(std::move(content));
""",
        """  ConfigParser cp(std::move(content));

  // /proc fd links expose guest paths; configuration directories use physical paths.
  char physical_binary[PATH_MAX];
  const char* config_binary = guestPath(AT_FDCWD, binary_realpath, physical_binary);
  if (config_binary == nullptr) {
    *error_msg = std::string("error resolving executable \\"") + binary_realpath +
                 "\\": " + strerror(errno);
    return false;
  }
""",
    ),
    (
        """    if (result == ConfigParser::kSection || result == ConfigParser::kEndOfFile) {
      return false;
    }""",
        """    if (result == ConfigParser::kSection || result == ConfigParser::kEndOfFile) {
      *error_msg = create_error_msg(ld_config_file_path, cp.lineno(),
          std::string("no dir section matches executable \\"") + config_binary + "\\"");
      return false;
    }""",
    ),
    (
        "      if (file_is_under_dir(binary_realpath, resolved_path)) {",
        "      if (config_matches_binary(config_binary, resolved_path)) {",
    ),
    (
        """    if (!namespace_configs.contains(linked_ns_name)) {""",
        """    if (namespace_configs.find(linked_ns_name) == namespace_configs.end()) {""",
    ),
    (
        """      if (properties->contains(name)) {""",
        """      if (properties->find(name) != properties->end()) {""",
    ),
    (
        """      if (!properties->contains(name)) {""",
        """      if (properties->find(name) == properties->end()) {""",
    ),
]

LINKER_UTILS_CPP = [
    (
        '#include "android-base/strings.h"\n',
        '#include "android-base/strings.h"\n'
        '#include "GuestPaths.h"\n'
        '#include "LinkerFileStat.h"\n',
    ),
    (
        """  char resolved_path[PATH_MAX];
  const char* original_path = path.c_str();
  if (realpath(original_path, resolved_path) != nullptr) {
    struct stat s;
    if (stat(resolved_path, &s) == -1) {""",
        """  char resolved_path[PATH_MAX];
  const char* original_path = path.c_str();
  // Namespace paths are written in host coordinates by GuestRuntime, while
  // realpath() works in guest coordinates. Strip the guest root before
  // resolving so the same directory is not addressed twice.
  if (guestPhysicalPath(original_path)) {
    original_path += strlen(guestRoot());
    if (!original_path[0]) original_path = "/";
  }
  if (realpath(original_path, resolved_path) != nullptr) {
    struct stat s;
    if (linkerPathStat(resolved_path, &s) == -1) {""",
    ),
]

LINKER_SOINFO_CPP = [
    (
        """           g_soinfo_handles_map.contains(handle_));""",
        """           g_soinfo_handles_map.find(handle_) != g_soinfo_handles_map.end());""",
    ),
]

LINKER_PHDR_CPP = [
    (
        """bool page_size_migration_supported() {
#if defined(__LP64__)
  static bool pgsize_migration_enabled = []() {
    std::string enabled;
    if (!android::base::ReadFileToString("/sys/kernel/mm/pgsize_migration/enabled", &enabled)) {
      return false;
    }
    return enabled.find("1") != std::string::npos;
  }();
  return pgsize_migration_enabled;
#else
  return false;
#endif
}""",
        """bool page_size_migration_supported() {
  // Android 15's linker hints the kernel about the padding pages of every LOAD
  // segment that is aligned more coarsely than the runtime page size:
  // ElfReader::DropPaddingPages() issues madvise(MADV_DONTNEED) on them, and
  // ElfReader::ReadPadSegmentNote() accepts NT_ANDROID_TYPE_PAD_SEGMENT.
  //
  // The guest libraries shipped by the Redroid 15 image are all 16 KiB aligned
  // (p_align=0x4000, .note.android.pad_segment present) while this container
  // runs on the host's 4 KiB kernel. The device's vendor kernel
  // (6.6.66-android15, /sys/kernel/mm/pgsize_migration/enabled=1) does not
  // survive that hint: the machine reboots within milliseconds of the first
  // padded library being mapped, and every /data write of the boot attempt is
  // lost (startup-events.log and art.log come back zeroed).
  //
  // There is nothing to migrate here - one page size, no APEX re-pinning, no
  // package manager - so the hint is disabled outright. The 16 KiB aligned
  // segments then map like any other ELF on a 4 KiB kernel, which is exactly
  // how the A9/A10 domains (whose bionic predates this feature) already work.
  return false;
}""",
    ),
]

LINKER_MAIN_CPP = [
    (
        '#include <bionic/libc_init_common.h>\n',
        '#include <bionic/libc_init_common.h>\n'
        '// A10VM guest patch: this linker also runs embedded (see below).\n'
        'extern "C" const char* __progname;\n',
    ),
    (
        "  __libc_shared_globals()->init_progname = g_argv[0];\n",
        "  __libc_shared_globals()->init_progname = g_argv[0];\n"
        "  // A10VM guest patch: the loader is also mapped as a library inside the\n"
        "  // host app process, where __linker_init() is the only initialiser that\n"
        "  // runs: __libc_init_common() (and therefore setprogname()) is not part\n"
        "  // of that path, so this binary's private copy of __progname stays null.\n"
        "  // liblog resolves a NULL log tag through GetDefaultTag(), which does\n"
        "  // std::string(getprogname()); strlen(NULL) then kills the process.\n"
        "  // Real device 2026-11-01 (instance 1793504908164): every apk-zip .so\n"
        "  // miss (LocalSend libEGL.so, MT Manager liblog.so, WebView liblog.so)\n"
        "  // died at __strlen_aarch64+0x10 with x0=0, fault=0x0.\n"
        "  if (__progname == nullptr) __progname = g_argv[0];\n",
    ),
    # A10VM guest patch: restore the caller's argv[0] for every guest exec.
    #
    # GuestExec.cpp hands the child's original argv[0] over in
    # A10VM_EXEC_ARGV0 precisely so a multi-call binary keeps its applet name,
    # but this script never applied that half of the contract (platform.patch
    # has it; patch-linker-a15.py did not), so the built linker contained no
    # A10VM_EXEC_ARGV0 string at all:
    #
    #   python -c "print(b'A10VM_EXEC_ARGV0' in
    #       open('runtime/build/bin/source-linker64','rb').read())"
    #   -> False        (state before round 123)
    #
    # The guest therefore ran /system/bin/id with argv[0] = the physical
    # executable path, whose basename is "toybox"; toybox reads that as "no
    # applet name" and takes the first real argument as its command:
    #
    #   toybox: Unknown command -u (see "toybox --help")
    #   java.lang.RuntimeException: ... Get shell's uid failed
    #   ANR in bin.mt.plus ... code=137
    #
    # (instance 1793525278509, vm/application-27156-47.log:46966/67546/67550).
    # /system/bin/id, sh, cmd, getprop and dumpsys are all toybox applets, so
    # this one missing half breaks every shell command a third-party app runs.
    #
    # Kernel semantics: execve() keeps the caller's argv[0]; the path merely
    # locates the image. exe_to_load was captured before this point, so
    # overwriting the slot keeps the executable for the loader while making
    # g_argv[0] the caller's string, exactly like a device execve().
    (
        "      exe_to_load = args.argv[1];\n"
        "      __libc_shared_globals()->initial_linker_arg_count = 1;\n",
        "      exe_to_load = args.argv[1];\n"
        "      __libc_shared_globals()->initial_linker_arg_count = 1;\n"
        "      // A10VM guest patch: argv[0] is the caller's string, not the\n"
        "      // physical executable path (see patch-linker-a15.py).\n"
        "      const char* guest_argv0 = getenv(\"A10VM_EXEC_ARGV0\");\n"
        "      if (guest_argv0 != nullptr && guest_argv0[0] != '\\0') {\n"
        "        args.argv[__libc_shared_globals()->initial_linker_arg_count] =\n"
        "            const_cast<char*>(guest_argv0);\n"
        "      }\n",
    ),
]

EDITS = {
    "linker.cpp": LINKER_CPP,
    "linker_config.cpp": LINKER_CONFIG_CPP,
    "linker_utils.cpp": LINKER_UTILS_CPP,
    "linker_soinfo.cpp": LINKER_SOINFO_CPP,
    "linker_phdr.cpp": LINKER_PHDR_CPP,
    "linker_main.cpp": LINKER_MAIN_CPP,
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name in SOURCES:
        source = (UP / name).read_text(encoding="utf-8")
        text = source
        for old, new in EDITS[name]:
            count = text.count(old)
            if count != 1:
                raise SystemExit(
                    "patch-linker-a15: anchor matched %d times in %s\n--- anchor ---\n%s"
                    % (count, name, old[:400]))
            text = text.replace(old, new, 1)
        if text == source:
            raise SystemExit("patch-linker-a15: %s unchanged" % name)
        (OUT / name).write_text(text, encoding="utf-8")
        print("patched: %s" % (OUT / name))


if __name__ == "__main__":
    main()
