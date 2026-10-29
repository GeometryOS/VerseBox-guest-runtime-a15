#!/bin/sh
# Build the API 35 static Bionic archive used by ld-android.so.
#
# Android 15 keeps the same static-libc shape as A9/A10 but the property area is
# now split across more files and the allocator is scudo. The project patch
# (vendor/properties/*.patch) is still applied to a derived copy so the source
# tree stays pristine; a patch that no longer applies is a hard failure.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
RT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$RT/compile.sh"
UP="$RT/upstream"
B="$UP/bionic/libc"
OUT="$RT/build"
DERIVED="$OUT/derived"
mkdir -p "$OUT/obj/static" "$OUT/lib" "$OUT/generated"

python3 "$RT/select-libc-sources.py" "$UP" static > "$OUT/generated/static-sources.txt"
# Android 15 generates the per-syscall stubs (genrule syscalls-arm64) instead of
# checking in arch/arm64/syscalls/*.S. The private linker and the static libc
# both need them, so they go into the bootstrap archive.
python3 "$B/tools/gensyscalls.py" arm64 "$B/SYSCALLS.TXT" > "$OUT/generated/syscalls.S"
test -s "$OUT/generated/syscalls.S" || { echo "gensyscalls produced no output" >&2; exit 1; }
# Android 15 moved the AID table to system/core/libcutils/include/private.
python3 "$RT/gen-android-ids.py" \
    "$UP/system/core/libcutils/include/private/android_filesystem_config.h" \
    > "$OUT/generated/generated_android_ids.h"

# Property area: derive a copy and apply the project edits there. Android 15's
# files differ enough that the A9 unified diff cannot apply; the edits are
# anchored in patch-properties-a15.py and fail loudly when an anchor is gone.
rm -rf "$DERIVED"
mkdir -p "$DERIVED/bionic/libc" "$DERIVED/core/property_service"
cp -R "$B/system_properties" "$DERIVED/bionic/libc/"
mkdir -p "$DERIVED/bionic/libc/bionic"
cp "$B/bionic/system_property_api.cpp" "$DERIVED/bionic/libc/bionic/"
cp -R "$UP/system/core/property_service/libpropertyinfoparser" "$DERIVED/core/property_service/"
python3 "$RT/patch-properties-a15.py" "$DERIVED"

BUILD_KIND=static
. "$MODULE/vendor/runtime/filesystem/sources.sh"
# Incremental by default: only sources newer than their object are recompiled.
# A15_FORCE=1 forces a clean build (use it whenever a shared flag changes).
stale() { [ "${A15_FORCE:-0}" = 1 ] && return 0; [ ! -f "$2" ] || [ "$1" -nt "$2" ]; }
objects=
while read -r path; do
    path=$(printf '%s' "$path" | tr -d '\r')
    [ -n "$path" ] || continue
    if guest_fs_replaces "$path"; then continue; fi
    # AOSP links the linker against libc_nomalloc, which excludes the allocator:
    # ld-android.so provides malloc/free in linker_memory.cpp (block allocator).
    # Shipping libc's allocator here pulls scudo/gwp_asan into ld-android.so and
    # their early initialisation runs before the linker is ready -> SIGSEGV.
    case "$path" in
        external/scudo/*|external/gwp_asan/*) continue ;;
        bionic/libc/bionic/malloc_common.cpp|bionic/libc/bionic/malloc_common_dynamic.cpp|\
        bionic/libc/bionic/malloc_heapprofd.cpp|bionic/libc/bionic/malloc_limit.cpp|\
        bionic/libc/bionic/gwp_asan_wrappers.cpp|bionic/libc/bionic/heap_tagging.cpp|\
        bionic/libc/bionic/android_mallopt.cpp|bionic/libc/bionic/jemalloc_wrapper.cpp)
            continue ;;
    esac
    case "$path" in
        bionic/libc/arch-arm64/syscalls/prctl.S) continue ;;
    esac
    object="$OUT/obj/static/$(printf '%s' "$path" | tr / _).o"
    if stale "$UP/$path" "$object"; then
        case "$path" in
            bionic/libc/arch-arm64/bionic/syscall.S)
                compile "$UP/$path" "$object" -Dsyscall=a10vm_kernel_syscall ;;
            *) compile "$UP/$path" "$object" ;;
        esac
    fi
    objects="$objects $object"
done < "$OUT/generated/static-sources.txt"

vendor_objects=
for path in GuestPaths GuestFileCalls GuestProcMaps GuestExec GuestRealpath \
            GuestOwnership GuestIdentity GuestXattrs GuestTimers GuestSockets \
            GuestSandbox GuestSyscalls; do
    object="$OUT/obj/static/$path.o"
    source="$MODULE/vendor/runtime/filesystem/$path.cpp"
    if stale "$source" "$object"; then
        if [ "$path" = GuestSyscalls ]; then
            compile "$source" "$object"
        else
            compile "$source" "$object" -Dsyscall=a10vm_kernel_syscall
        fi
    fi
    objects="$objects $object"
    vendor_objects="$vendor_objects $object"
done
object="$OUT/obj/static/GuestSyscalls.S.o"
if stale "$MODULE/vendor/runtime/filesystem/GuestSyscalls.S" "$object"; then
    compile "$MODULE/vendor/runtime/filesystem/GuestSyscalls.S" "$object"
fi
objects="$objects $object"
vendor_objects="$vendor_objects $object"

# The project bridges replace a subset of the generated syscall stubs. Collect
# their defined symbols and drop the matching ENTRY blocks instead of letting the
# linker see two definitions of __connect/__openat/inotify_add_watch.
: > "$OUT/obj/static/vendor-symbols.txt"
for object in $vendor_objects; do
    if command -v cygpath >/dev/null 2>&1; then nm_path=$(cygpath -w "$object"); else nm_path="$object"; fi
    llvm-nm -g --defined-only "$nm_path" 2>/dev/null | awk '{print $NF}' >> "$OUT/obj/static/vendor-symbols.txt" || true
done
python3 "$RT/filter-syscalls.py" "$OUT/generated/syscalls.S" \
    "$OUT/obj/static/vendor-symbols.txt" "$OUT/generated/syscalls-filtered.S"
object="$OUT/obj/static/generated_syscalls.S.o"
compile "$OUT/generated/syscalls-filtered.S" "$object"
objects="$objects $object"

for path in system_properties/context_node.cpp system_properties/contexts_serialized.cpp \
            system_properties/prop_area.cpp system_properties/prop_info.cpp \
            system_properties/system_properties.cpp bionic/system_property_api.cpp \
            async_safe/async_safe_log.cpp; do
    object="$OUT/obj/static/property_$(basename "$path" .cpp).o"
    if [ -f "$DERIVED/bionic/libc/$path" ]; then source="$DERIVED/bionic/libc/$path";
    else source="$B/$path"; fi
    if stale "$source" "$object"; then compile "$source" "$object" -I"$OUT/generated"; fi
    objects="$objects $object"
done
object="$OUT/obj/static/property_info_parser.o"
if stale "$DERIVED/core/property_service/libpropertyinfoparser/property_info_parser.cpp" "$object"; then
    compile "$DERIVED/core/property_service/libpropertyinfoparser/property_info_parser.cpp" "$object"
fi
objects="$objects $object"

rm -f "$OUT/lib/liba15vm-bootstrap.a"
# Windows caps the command line at ~32 KiB and 524 object paths overflow it.
# llvm-ar accepts an @response file, and the native llvm-ar.exe needs Windows
# paths, so cygpath converts the MSYS paths first.
: > "$OUT/obj/static-response.txt"
for object in $objects; do
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$object" >> "$OUT/obj/static-response.txt"
    else
        printf '%s\n' "$object" >> "$OUT/obj/static-response.txt"
    fi
done
llvm-ar rcD "$OUT/lib/liba15vm-bootstrap.a" @"$(cygpath -w "$OUT/obj/static-response.txt" 2>/dev/null || printf '%s' "$OUT/obj/static-response.txt")"
echo "static archive: $OUT/lib/liba15vm-bootstrap.a"
