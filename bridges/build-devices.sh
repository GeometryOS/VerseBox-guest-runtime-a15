#!/bin/sh
# Build the API 35 device/policy bridges:
#   liba15vm-guest-devices.so   uevent / wake-lock shims (no host device bus)
#   liba15vm-manager-policy.so  package/permission policy shim
#
# Same contract as the A9 recipe, rebuilt against the API 35 runtime. The A15
# image's headers are used for compile-time declarations only; the .so links
# the source-built API 35 libc, never an A9/A10 object.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
. "$(dirname -- "$0")/compile.sh"

GEN="$MODULE/vendor/native"
OBJ="$OUT/obj/devices"
mkdir -p "$OBJ" "$OUT/lib"
test -f "$RT/build/lib/libc.so" || { echo "run build-libc.sh first" >&2; exit 1; }

crt_so
builtins=$(clang --target=$TARGET --print-libgcc-file-name)

device_objects=
for path in GuestDevices.cpp HostLinearMemory.cpp HostDeviceTransport.cpp GuestSharedMemory.cpp; do
    object="$OBJ/$(basename "$path").o"
    platform_compile "$GEN/$path" "$object"
    device_objects="$device_objects $object"
done
ld.lld -shared -soname liba15vm-guest-devices.so -z now -z relro -z max-page-size=4096 \
    --no-undefined "$OUT/obj/crt/crtbegin_so.o" $device_objects \
    "$LIBCXX" "$LIBC" "$LIBDL" "$builtins" \
    "$OUT/obj/crt/crtend_so.o" -o "$OUT/lib/liba15vm-guest-devices.so"

platform_compile "$GEN/ManagerPolicy.c" "$OBJ/manager-policy.o"
ld.lld -shared -soname liba15vm-manager-policy.so -z now -z relro -z max-page-size=4096 \
    --no-undefined "$OUT/obj/crt/crtbegin_so.o" "$OBJ/manager-policy.o" \
    "$LIBC" "$LIBDL" "$OUT/obj/crt/crtend_so.o" -o "$OUT/lib/liba15vm-manager-policy.so"

for f in "$OUT/lib/liba15vm-guest-devices.so" "$OUT/lib/liba15vm-manager-policy.so"; do
    llvm-readelf -hlWd "$f" > "$f.elf.txt"
done
echo "devices: $OUT/lib/liba15vm-guest-devices.so"
echo "devices: $OUT/lib/liba15vm-manager-policy.so"
