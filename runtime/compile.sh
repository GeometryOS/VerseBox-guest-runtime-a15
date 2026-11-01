#!/bin/sh
# Shared compile helpers for the API 35 guest runtime.
#
# Android 15 moved several headers: liblog is system/logging/liblog and the
# property-info parser is system/libpropertyinfoparser. Paths are probed here so
# a source-layout change fails once, in this file, instead of in every target.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
RT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$RT/../.." && pwd)
UP="$RT/upstream"
OUT="$RT/build"
B="$UP/bionic/libc"
NDK=${ANDROID_NDK_HOME:-"C:/Users/22437/AppData/Local/Android/Sdk/ndk/29.0.14206865"}
if command -v cygpath >/dev/null 2>&1; then NDK=$(cygpath -u "$NDK"); fi
case "$(uname -s 2>/dev/null || echo Linux)" in
    MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
    *) NDK_HOST=linux-x86_64 ;;
esac
export PATH="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin:$PATH"
NDK_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST"
unset LD_LIBRARY_PATH LD_PRELOAD || true
CLANG_RESOURCE_DIR=$(clang -print-resource-dir)

# Page size: the guest runs on the host's kernel, and every supported host here
# uses 4 KiB pages. 16 KiB-aligned guest libraries load fine on a 4 KiB kernel,
# but a 4 KiB-aligned guest library cannot load on a 16 KiB kernel. Keep the
# runtime at 4K and record the host kernel's page size in the manifest evidence.
PAGE_SIZE=${A15VM_PAGE_SIZE:-4096}

have() { [ -e "$1" ]; }
LOGINCLUDE="$UP/system/logging/liblog/include"
[ -d "$LOGINCLUDE" ] || LOGINCLUDE="$UP/system/core/liblog/include"
PROPINC="$UP/system/libpropertyinfoparser/include"
[ -d "$PROPINC" ] || PROPINC="$UP/system/core/property_service/libpropertyinfoparser/include"
JEMALLOC="$UP/external/jemalloc_new"
[ -d "$JEMALLOC" ] || JEMALLOC="$UP/external/jemalloc"

compile() {
    input=$1
    object=$2
    shift 2
    case "$input" in
        */bionic/libc/*|*/bionic/libdl/*|*/filesystem/*) cxx_include="$UP/bionic/libstdc++/include"; cpp_atomic= ;;
        # Android 15's real libc++ lives in external/llvm-project, which gitiles
        # cannot archive as a subtree. The NDK ships the same LLVM libc++ (21)
        # headers for API 35; the linker links the matching static archives.
        *) cxx_include="$NDK_TOOLCHAIN/sysroot/usr/include/c++/v1"; cpp_atomic=-D_USING_LIBCXX ;;
    esac
    # Android 15's bionic and its system libraries (libbase uses
    # string_view::starts_with) are C++20. Keep every C++ translation unit on
    # the same standard so a header/library ABI split cannot creep in.
    cpp_std=gnu++20
    set -- --target=aarch64-linux-android35 -nostdinc \
        -isystem "$cxx_include" \
        -isystem "$CLANG_RESOURCE_DIR/include" \
        -isystem "$B/include" -isystem "$B/kernel/uapi/asm-arm64" \
        -isystem "$B/kernel/uapi" -isystem "$B/kernel/android/uapi" \
        -isystem "$B/platform" \
        -I"$B" -I"$B/async_safe/include" -I"$B/private" -I"$OUT/generated" \
        -I"$B/stdio" -I"$B/upstream-openbsd/android/include" \
        -I"$B/upstream-openbsd/lib/libc/include" \
        -I"$B/upstream-openbsd/lib/libc/stdio" -I"$B/upstream-openbsd/lib/libc/gdtoa" \
        -I"$B/upstream-freebsd/android/include" \
        -I"$B/upstream-netbsd/android/include" -I"$B/upstream-netbsd/lib/libc/include" \
        -I"$B/dns/include" -I"$B/tzcode" \
        -I"$OUT/derived/bionic/libc/system_properties/include" \
        -I"$OUT/derived/core/property_service/libpropertyinfoparser/include" \
        -I"$B/system_properties/include" \
        -I"$UP/bionic/linker" -I"$UP/system/core/base/include" \
        -I"$LOGINCLUDE" -I"$UP/system/core/libutils/include" \
        -I"$UP/system/libbase/include" -I"$UP/system/libziparchive/include" \
        -I"$UP/system/libziparchive/incfs_support/include" -I"$UP/system/libziparchive" \
        -I"$UP/system/core/libcutils/include" -I"$UP/system/core/libsystem/include" \
        -I"$UP/system/core/include" -I"$UP/system/core/libziparchive/include" \
        -I"$UP/external/zlib" -I"$UP/external/zlib/src" \
        -I"$UP/external/libcxxabi/include" -I"$JEMALLOC/include" \
        -I"$PROPINC" \
        -I"$UP/external/gwp_asan" -I"$UP/external/scudo" \
        -I"$UP/external/arm-optimized-routines" -I"$UP/external/llvm-libc" \
        -I"$MODULE/vendor/runtime/filesystem" \
        -fPIC -fno-stack-protector -fno-exceptions -fno-rtti -fno-sized-deallocation \
        -fno-emulated-tls -ffunction-sections -fdata-sections -mno-outline-atomics \
        -O2 -D_LIBC=1 -DUSE_SCUDO $cpp_atomic -U_FORTIFY_SOURCE \
        -DLIBLOG_LOG_TAG=1006 -DSNET_EVENT_LOG_TAG=1397638484 \
        -MD -MF "$object.d" "$@"
    if [ "${BUILD_KIND:-}" = linker ]; then
        set -- "$@" -DLIBC_STATIC -fvisibility=hidden '-DLIB_PATH="lib64"'
        # AOSP builds ld-android and its support libraries as release code, so
        # log/log.h resolves LOG_NDEBUG to 1 through NDEBUG and strips ALOGV.
        # Without NDEBUG every ALOGV survives into the loader; libziparchive
        # logs "Zip: Unable to find entry" with the default LOG_TAG (NULL), and
        # liblog resolves NULL tags through GetDefaultTag() ->
        # std::string(getprogname()). The linker's own __progname is never set
        # (it runs embedded, see patch-linker-a15.py), so that strlen(NULL)
        # killed the app process on every "zip entry not found" lookup:
        # MT Manager / LocalSend / WebView all died at pc=__strlen_aarch64
        # +0x10 with x0=0 (instance 1793504908164, 2026-11-01 12:00).
        set -- "$@" -DNDEBUG
    else
        set -- "$@" -DPLATFORM_SDK_VERSION=35
        # Android 15's bionic_ifuncs.h only defines DEFINE_IFUNC_FOR when one of
        # these is set; Soong normally adds it per libc variant.
        if [ "${BUILD_KIND:-}" = static ]; then
            set -- "$@" -DBIONIC_STATIC_DISPATCH
        else
            set -- "$@" -DBIONIC_DYNAMIC_DISPATCH
        fi
    fi
    case "$input" in
        */bionic/fts.c) set -- "$@" -include openbsd-compat.h ;;
        */upstream-openbsd/*|*/stdio/vfprintf.cpp|*/stdio/vfwprintf.cpp)
            set -- "$@" -include openbsd-compat.h ;;
        */upstream-freebsd/*) set -- "$@" -include freebsd-compat.h ;;
        # This must precede the generic upstream-netbsd pattern: POSIX case
        # takes the first match only.
        */upstream-netbsd/lib/libc/stdlib/*rand48.c|\
        */upstream-netbsd/lib/libc/stdlib/seed48.c|\
        */upstream-netbsd/lib/libc/stdlib/lcong48.c)
            set -- "$@" -include netbsd-compat.h -DPOSIX_MISTAKE -fcommon ;;
        */upstream-netbsd/*) set -- "$@" -include netbsd-compat.h -DPOSIX_MISTAKE ;;
        */dns/*) set -- "$@" -include netbsd-compat.h -DANDROID_CHANGES -DINET6 ;;
        */tzcode/*) set -- "$@" -DALL_STATE -DSTD_INSPIRED -DTHREAD_SAFE \
            -DTM_GMTOFF=tm_gmtoff '-DTZDIR="/system/usr/share/zoneinfo"' \
            -DHAVE_POSIX_DECLS=0 -DUSG_COMPAT=2 -DHAVE_TZNAME=2 -DHAVE_STDBOOL_H \
            '-DWILDABBR=""' \
            -DNO_RUN_TIME_WARNINGS_ABOUT_YEAR_2000_PROBLEMS_THANK_YOU -Dlint ;;
        */bionic/fortify.cpp) set -- "$@" -D__BIONIC_DECLARE_FORTIFY_HELPERS -DNO___MEMCPY_CHK ;;
        */bionic/open.cpp) set -- "$@" -D__BIONIC_DECLARE_FORTIFY_HELPERS ;;
        */external/libcxxabi/src/*) set -- "$@" -DLIBCXXABI_SILENT_TERMINATE \
            -D_LIBCXXABI_BUILDING_LIBRARY -D_LIBCXXABI_NO_EXCEPTIONS ;;
        */external/llvm-libc/*) set -- "$@" -DLIBC_NAMESPACE=llvmlibc \
            -DLIBC_COPT_PUBLIC_PACKAGING \
            '-DLLVM_LIBC_FUNCTION_ATTR=__attribute__((visibility("default")))' ;;
        # bionic's scudo target block (external/scudo/Android.bp). The allocator
        # wrappers must see _BIONIC=1, the platform TLS slot and the 16-byte
        # minimum alignment, or libc exports a mismatched malloc family.
        */external/scudo/*) set -- "$@" -DSCUDO_USE_CUSTOM_CONFIG -D_BIONIC=1 \
            -DSCUDO_HAS_PLATFORM_TLS_SLOT -DSCUDO_MIN_ALIGNMENT_LOG=4 \
            -DHAVE_ANDROID_UNSAFE_FRAME_POINTER_CHASE -mcrc \
            -I"$UP/external/scudo/standalone/include" \
            -I"$UP/external/scudo/standalone" -I"$UP/external/scudo/config" ;;
        */system/core/liblog/*.c|*/system/logging/liblog/*.c) set -- "$@" -fcommon ;;
        # liblog's Soong default is a non-debuggable build (Android.bp line 137).
        */system/logging/liblog/*.cpp) set -- "$@" -DANDROID_DEBUGGABLE=0 ;;
        # A15's libziparchive default module disables the incfs hardening path.
        */system/libziparchive/*) set -- "$@" -DINCFS_SUPPORT_DISABLED=1 -DZLIB_CONST ;;
        # AOSP's jemalloc is built without _GNU_SOURCE: bionic then declares the
        # POSIX strerror_r (int), which util.c expects.
        */external/jemalloc*/src/*) set -- "$@" \
            -D_REENTRANT -DANDROID_MAX_ARENAS=2 \
            -DANDROID_LG_TCACHE_MAXCLASS_DEFAULT=16 \
            -DANDROID_TCACHE_NSLOTS_SMALL_MAX=8 \
            -DANDROID_TCACHE_NSLOTS_LARGE=16 -DJEMALLOC_TCACHE \
            -DANDROID_LG_CHUNK_DEFAULT=21 -fvisibility=hidden \
            -I"$JEMALLOC/src" -I"$JEMALLOC/include" \
            -include "$B/async_safe/include/async_safe/log.h" ;;
    esac
    case "$input" in
        */external/libcxx/src/*) clang++ "$@" -nostdinc++ -std=gnu++17 -D_LIBCPP_BUILDING_LIBRARY \
            -DLIBCXX_BUILDING_LIBCXXABI -c "$input" -o "$object" ;;
        */external/libcxxabi/src/*) clang++ "$@" -nostdinc++ -std=gnu++17 -c "$input" -o "$object" ;;
        *.cpp|*.cc) clang++ "$@" -nostdinc++ -std="$cpp_std" -c "$input" -o "$object" ;;
        *.c) clang "$@" -std=gnu99 -c "$input" -o "$object" ;;
        *.S) clang "$@" -D__ASSEMBLY__ -c "$input" -o "$object" ;;
        *) printf 'Unsupported source: %s\n' "$input" >&2; exit 1 ;;
    esac
}
