#!/bin/sh
# Build the API 35 shared libm.so from the AOSP 15 Bionic math sources.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
RT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$RT/compile.sh"
UP="$RT/upstream"
B="$UP/bionic/libc"
OUT="$RT/build"
mkdir -p "$OUT/obj/libm" "$OUT/lib" "$OUT/generated"
test -f "$OUT/lib/libc.so" || { echo "run build-libc.sh first" >&2; exit 1; }

python3 "$RT/select-module-sources.py" "$UP" "$UP/bionic/libm/Android.bp" libm arm64 \
    > "$OUT/generated/libm-sources.txt"
if [ -f "$UP/bionic/libm/libm.map.txt" ]; then
    if [ -f "$UP/bionic/tools/generate-version-script.py" ]; then
        python3 "$UP/bionic/tools/generate-version-script.py" arm64 \
            "$UP/bionic/libm/libm.map.txt" "$OUT/generated/libm.map"
    else
        tr -d '\r' < "$UP/bionic/libm/libm.map.txt" > "$OUT/generated/libm.map"
    fi
else
    tr -d '\r' < "$UP/bionic/libm/libm.arm64.map" > "$OUT/generated/libm.map"
fi

builtins=$(clang --target=aarch64-linux-android35 --print-libgcc-file-name)
test -f "$builtins"
compile "$B/arch-common/bionic/crtbegin_so.c" "$OUT/obj/libm/crtbegin_so.o"
compile "$B/arch-common/bionic/crtend_so.S" "$OUT/obj/libm/crtend_so.o"

objects=
extra="-D_LIBC=1 -D__BIONIC_NO_MATH_INLINES -D_BSD_SOURCE -DFLT_EVAL_METHOD=0 \
    -I$UP/bionic/libm -I$UP/bionic/libm/upstream-freebsd/android/include \
    -I$UP/bionic/libm/upstream-freebsd/lib/msun/src \
    -I$UP/bionic/libm/upstream-freebsd/lib/msun/ld128 -I$B \
    -Wno-missing-braces -Wno-parentheses -Wno-sign-compare -Wno-uninitialized \
    -Wno-unknown-pragmas -Wno-macro-redefined -Wno-deprecated-non-prototype \
    -Wno-implicit-const-int-float-conversion"
while read -r path; do
    path=$(printf '%s' "$path" | tr -d '\r')
    [ -n "$path" ] || continue
    object="$OUT/obj/libm/$(printf '%s' "$path" | tr / _).o"
    # Absolute path: the generic -I order would otherwise resolve
    # "freebsd-compat.h" to libc's copy, which lacks digittoint.
    # Assembler sources must not pre-include C headers.
    case "$path" in
        *.S) compile "$UP/$path" "$object" -I"$UP/bionic/libm" -I"$UP/bionic/libm/arm64" ;;
        *) compile "$UP/$path" "$object" $extra -include "$UP/bionic/libm/freebsd-compat.h" ;;
    esac
    objects="$objects $object"
done < "$OUT/generated/libm-sources.txt"

# Android 15's libm no longer carries exp/pow/log/sin/cos in the FreeBSD msun
# tree: bionic/libm/Android.bp pulls them in with
#   whole_static_libs: ["libarm-optimized-routines-math"]
# so those 23 files are part of libm.so and must be linked here as well.
AOR="$UP/external/arm-optimized-routines"
test -d "$AOR/math" || { echo "missing external/arm-optimized-routines (run fetch-a15-sources.sh)" >&2; exit 1; }
aor_flags="-D__BIONIC_LP32_USE_LONG_DOUBLE -DFLT_EVAL_METHOD=0 -ffp-contract=fast \
    -fno-math-errno -DWANT_ERRNO=0 -DWANT_VMATH=0 -DWANT_SIMD_TESTS=0 \
    -DWANT_EXP10_TESTS=0 -DWANT_MOPS=1 \
    -I$AOR/math/include -I$AOR/math"
for path in cosf exp exp2 exp2f exp2f_data exp_data expf log log2 log2_data \
            log2f log2f_data log_data logf logf_data math_err math_errf pow \
            pow_log_data powf powf_log2_data sincosf sincosf_data sinf; do
    object="$OUT/obj/libm/aor_math_$path.c.o"
    input="$AOR/math/$path.c"
    if [ ! -f "$object" ] || [ "$input" -nt "$object" ]; then
        compile "$input" "$object" $aor_flags
    fi
    objects="$objects $object"
done

ld.lld -shared -soname libm.so -z max-page-size=$PAGE_SIZE -z now -z relro \
    --hash-style=gnu --eh-frame-hdr --build-id=sha1 --no-undefined \
    -Bsymbolic-functions --exclude-libs ALL \
    --version-script "$OUT/generated/libm.map" \
    "$OUT/obj/libm/crtbegin_so.o" $objects "$builtins" "$OUT/lib/libc.so" \
    "$OUT/obj/libm/crtend_so.o" -o "$OUT/lib/libm.so"
llvm-readelf -hlWd "$OUT/lib/libm.so" > "$OUT/libm-elf.txt"
llvm-nm -u "$OUT/lib/libm.so" > "$OUT/libm-undefined.txt"
echo "libm: $OUT/lib/libm.so"
