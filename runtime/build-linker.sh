#!/bin/sh
# Build the API 35 private ld-android.so (source-built).
#
# Android 15's linker already implements direct execution ("linker64 PROGRAM"),
# so unlike A9 there is no load_executable() port here. The project only applies
# the private-configuration contract (patch-linker-a15.py).
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
RT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$RT/compile.sh"
UP="$RT/upstream"
OUT="$RT/build"
B="$UP/bionic/libc"
mkdir -p "$OUT/obj/linker" "$OUT/obj/support" "$OUT/bin" "$OUT/generated" "$OUT/derived/bionic/linker"
test -f "$OUT/lib/liba15vm-bootstrap.a" || { echo "run build-static.sh first" >&2; exit 1; }

python3 "$RT/patch-linker-a15.py"

builtins=$(clang --target=aarch64-linux-android35 --print-libgcc-file-name)
test -f "$builtins"
unwind=$(ls "$NDK_TOOLCHAIN"/lib/clang/*/lib/linux/aarch64/libunwind.a 2>/dev/null | head -n 1)
test -f "$unwind"

BUILD_KIND=linker
objects=
# Incremental by default: a full linker rebuild is only needed when a shared
# header changes. Set A15_FORCE=1 for a release build.
stale() { [ "${A15_FORCE:-0}" = 1 ] && return 0; [ ! -f "$2" ] || [ "$1" -nt "$2" ]; }
while read -r path; do
    [ -n "$path" ] || continue
    object="$OUT/obj/linker/$(printf '%s' "$path" | tr / _).o"
    if [ -f "$OUT/derived/$path" ]; then input="$OUT/derived/$path"; else input="$UP/$path"; fi
    if stale "$input" "$object"; then
        compile "$input" "$object" -I"$MODULE/vendor/runtime/filesystem" \
            -I"$MODULE/vendor/runtime/dynamic"
    fi
    objects="$objects $object"
done < "$RT/lists/linker-sources.txt"
object="$OUT/obj/linker/LinkerFileStat.o"
if stale "$MODULE/vendor/runtime/dynamic/LinkerFileStat.cpp" "$object"; then
    compile "$MODULE/vendor/runtime/dynamic/LinkerFileStat.cpp" "$object" -I"$MODULE/vendor/runtime/filesystem"
fi
objects="$objects $object"

support_objects=
. "$MODULE/vendor/runtime/filesystem/sources.sh"
while read -r path; do
    path=$(printf '%s' "$path" | tr -d '\r')
    [ -n "$path" ] || continue
    if guest_fs_replaces "$path"; then continue; fi
    object="$OUT/obj/support/$(printf '%s' "$path" | tr / _).o"
    if stale "$UP/$path" "$object"; then compile "$UP/$path" "$object"; fi
    support_objects="$support_objects $object"
done < "$RT/lists/support-sources.txt"
rm -f "$OUT/lib/liba15vm-linker-support.a"
: > "$OUT/obj/support-response.txt"
for object in $support_objects; do
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$object" >> "$OUT/obj/support-response.txt"
    else
        printf '%s\n' "$object" >> "$OUT/obj/support-response.txt"
    fi
done
llvm-ar rcD "$OUT/lib/liba15vm-linker-support.a" @"$(cygpath -w "$OUT/obj/support-response.txt" 2>/dev/null || printf '%s' "$OUT/obj/support-response.txt")"

# --pack-dyn-relocs=relr is NOT optional. Android 15's linker_main.cpp
# relocate_linker() only expands DT_RELR and DT_*_IRELATIVE entries, because
# AOSP links ld-android.so with relative relocations packed into RELR:
#
#     "The linker should only have relative relocations (in RELR) and
#      IRELATIVE relocations."
#
# A linker linked without RELR packing starts with an un-relocated GOT; the
# first GOT-based global access (soinfo::prelink_image reading
# g_linker_debug_config) dereferences NULL and the process dies with SIGSEGV
# (exit code 139) before it can print anything. The reference ld-android.so in
# the Redroid 15 image has DT_RELR/DT_RELRSZ/DT_RELRENT and only 13 IRELATIVE
# entries in .rela.dyn; a build without this flag has 2,616 RELATIVE entries
# and no DT_RELR.
ld.lld -shared -e _start -Bsymbolic --exclude-libs ALL -soname ld-android.so \
    --gc-sections -z max-page-size=$PAGE_SIZE -z now -z relro --hash-style=gnu \
    --pack-dyn-relocs=relr \
    --eh-frame-hdr --build-id=sha1 --no-undefined \
    --version-script "$UP/bionic/linker/linker.generic.map" \
    -Map "$OUT/linker.map" --error-limit=0 \
    $objects --start-group "$OUT/lib/liba15vm-linker-support.a" \
    "$OUT/lib/liba15vm-bootstrap.a" "$builtins" \
    "$NDK_TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_static.a" \
    "$NDK_TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++abi.a" \
    "$unwind" \
    --end-group \
    -o "$OUT/bin/source-linker64"
llvm-readelf -hlWd "$OUT/bin/source-linker64" > "$OUT/linker-elf.txt"
llvm-nm -u "$OUT/bin/source-linker64" > "$OUT/linker-undefined.txt"
echo "linker: $OUT/bin/source-linker64"
