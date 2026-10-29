#!/bin/sh
# Build the API 35 binder transport bridges:
#   liba15binder.so             project binder wire/driver core
#   binder-broker               independent broker (per-guest Binder device)
#   liba15vm-binder-devices.so  LD_PRELOAD shim that redirects /dev/binder
#
# A15 keeps the ROM's libbinder/libhidlbase; only the transport is project code.
# Differences from the A9 recipe (guest-runtime-a9/bridges/build-binder.sh):
#   * no bridges/compat/binder_compat.h: A15's kernel uapi already carries
#     FLAT_BINDER_FLAG_TXN_SECURITY_CTX and BR_TRANSACTION_SEC_CTX;
#   * no libhwbinder.so in this image at all, so the broker never links it.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
. "$(dirname -- "$0")/compile.sh"

SRC="$MODULE/vendor/binder/native"
OBJ="$OUT/obj/binder"
mkdir -p "$OBJ" "$OUT/lib" "$OUT/bin"
test -f "$RT/build/lib/libc.so" || { echo "run build-libc.sh first" >&2; exit 1; }
test -f "$MODULE/rootfs/system/lib64/libc++.so" || { echo "missing A15 image libc++" >&2; exit 1; }

crt_so
crt_pie
builtins=$(clang --target=$TARGET --print-libgcc-file-name)

# Project transport core. Ashmem.cpp / Scheduling.cpp replace libcutils pieces,
# so the library itself only depends on libc/libc++.
# NOTE: these sources deliberately contain no thread_local variables (same
# reason as A9): the linker rewrites TLS relocations, and per-thread state uses
# pthread keys instead (Driver.cpp / Wire.cpp / BinderDevices.cpp).
binder_objects=
for path in Wire.cpp TransactionLayout.cpp TransactionWire.cpp Driver.cpp \
            ClientBuffers.cpp Ashmem.cpp Scheduling.cpp; do
    object="$OBJ/$(basename "$path").o"
    platform_compile "$SRC/$path" "$object"
    binder_objects="$binder_objects $object"
done
ld.lld -shared -soname liba15binder.so -z now -z relro -z max-page-size=4096 \
    --hash-style=gnu --eh-frame-hdr --build-id=sha1 --no-undefined --exclude-libs ALL \
    "$OUT/obj/crt/crtbegin_so.o" $binder_objects \
    "$LIBCXX" "$LIBC" "$LIBM" "$LIBDL" "$builtins" \
    "$OUT/obj/crt/crtend_so.o" -o "$OUT/lib/liba15binder.so"
llvm-readelf -hlWd "$OUT/lib/liba15binder.so" > "$OUT/lib/liba15binder.elf.txt"

# Guest-side Binder endpoint shim.
platform_compile "$MODULE/vendor/native/BinderDevices.cpp" "$OBJ/binder-devices.o"
ld.lld -shared -soname liba15vm-binder-devices.so -z now -z relro -z max-page-size=4096 \
    --no-undefined "$OUT/obj/crt/crtbegin_so.o" "$OBJ/binder-devices.o" \
    "$OUT/lib/liba15binder.so" "$LIBC" "$LIBDL" "$OUT/obj/crt/crtend_so.o" \
    -o "$OUT/lib/liba15vm-binder-devices.so"

# Broker: one independent process per guest, talks the same wire protocol.
broker_objects=
for path in Broker.cpp BrokerCleanup.cpp BrokerDispatch.cpp BrokerMain.cpp \
            BrokerNodeReclaim.cpp BrokerObjects.cpp BrokerTransactions.cpp Wire.cpp; do
    object="$OBJ/broker-$(basename "$path").o"
    platform_compile "$SRC/$path" "$object"
    broker_objects="$broker_objects $object"
done
ld.lld -pie -e _start -dynamic-linker /system/bin/linker64 \
    -z now -z relro -z max-page-size=4096 --no-undefined --exclude-libs ALL \
    "$OUT/obj/crt/crtbegin.o" $broker_objects \
    "$OUT/lib/liba15binder.so" \
    "$MODULE/rootfs/system/lib64/libbase.so" \
    "$MODULE/rootfs/system/lib64/liblog.so" \
    "$MODULE/rootfs/system/lib64/libcutils.so" \
    "$MODULE/rootfs/system/lib64/libutils.so" \
    "$LIBCXX" "$LIBC" "$LIBM" "$LIBDL" "$LINKER" "$builtins" \
    "$OUT/obj/crt/crtend.o" -o "$OUT/bin/binder-broker"

for f in "$OUT/lib/liba15binder.so" "$OUT/lib/liba15vm-binder-devices.so" "$OUT/bin/binder-broker"; do
    llvm-readelf -hlWd "$f" > "$f.elf.txt"
done
echo "binder: $OUT/lib/liba15binder.so"
echo "binder: $OUT/lib/liba15vm-binder-devices.so"
echo "binder: $OUT/bin/binder-broker"
