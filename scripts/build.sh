#!/bin/sh
# One-command Android 15 (API 35) pipeline.
#
#   image  ->  rootfs  ->  APEX  ->  API 35 runtime  ->  bridges  ->  prepared  ->  APK
#
# Every stage is skipped when its product already exists, so a rerun after a Java
# or prepared change costs only that stage. A15_FORCE=1 rebuilds the native
# runtime and bridges from scratch (it does NOT re-download the image).
#
# Environment:
#   PYTHON          python interpreter            (default: python3, then python)
#取消注释下边这行来全量构建
#   A15_FORCE=1     force the native stages

#   A15_SKIP_APK=1  stop after the ROM package (no Gradle)
#   A15_SKIP_ROM=1  only produce the runtime directory (prepared), no ROM zip
#   A15_ROM_VERSION override the ROM version (default: rom/rom.properties.yaml)
#   A15_LIB_MODE    apk | rom | both   (default: whatever the properties file says)
#   A15_ROM_OUT     explicit output zip path (default: <runtime>/dist/...)
#   REDROID_TAG     pin a non-rolling redroid tag (see BUILD.md section 2)
#
# Flags are also accepted: --skip-rom --skip-apk --rom-version=X --lib-mode=Y --rom-out=Z
#
# Two product chains, do not mix them: the **ROM package** only depends on prepared
# (rom/pack-rom.py), the **APK** is produced by Gradle.
#
# Requirements: Python 3 (stdlib only), JDK + Android SDK for the APK, and for a
# native rebuild the Android NDK 29 plus a POSIX shell (this script itself).
set -eu
MODULE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$MODULE/.." && pwd)
cd "$MODULE"

PY=${PYTHON:-}
if [ -z "$PY" ]; then
    if command -v python3 >/dev/null 2>&1; then PY=python3
    elif command -v python >/dev/null 2>&1; then PY=python
    else echo "build.sh: no python3/python in PATH (set PYTHON=...)" >&2; exit 1
    fi
fi

FORCE=${A15_FORCE:-0}
SKIP_ROM=${A15_SKIP_ROM:-0}
SKIP_APK=${A15_SKIP_APK:-0}
ROM_VERSION=${A15_ROM_VERSION:-}
LIB_MODE=${A15_LIB_MODE:-}
ROM_OUT=${A15_ROM_OUT:-}
PLAN_ONLY=${A15_PLAN_ONLY:-0}
step() { printf '\n== %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-rom)      SKIP_ROM=1 ;;
        --skip-apk)      SKIP_APK=1 ;;
        --rom-version=*) ROM_VERSION=${1#*=} ;;
        --lib-mode=*)    LIB_MODE=${1#*=} ;;
        --rom-out=*)     ROM_OUT=${1#*=} ;;
        --plan-only)     PLAN_ONLY=1 ;;
        -h|--help)       sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "build.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done


# ROM 包的展示路径（尊重 --rom-out）
if [ -n "$ROM_OUT" ]; then ROM_SHOWN="$ROM_OUT"; else ROM_SHOWN="$(pwd)/dist"; fi

# APK（与宿主桌面渲染器）属于 **app 仓库**：只 clone 本仓库时必须干净地停在 ROM 包，
# 而不是跑到最后抛一个 "gradlew: not found"。判据是"app 工程的两个标志文件都在"。
has_app_repo=0
if { [ -f "$ROOT/gradlew" ] || [ -f "$ROOT/gradlew.bat" ]; } && [ -f "$ROOT/settings.gradle.kts" ]; then
    has_app_repo=1
fi

# --plan-only / A15_PLAN_ONLY=1：只打印计划，不跑任何重活（第三方排障用）
if [ "$PLAN_ONLY" = 1 ]; then
    step "plan-only"
    printf '  module        : %s\n' "$MODULE"
    printf '  root          : %s\n' "$ROOT"
    if [ "$has_app_repo" = 1 ]; then
        printf '  app repo      : found (%s/gradlew)\n' "$ROOT"
    else
        printf '  app repo      : not found -> will stop after the ROM package\n'
    fi
    if [ "$SKIP_ROM" = 1 ]; then
        printf '  ROM package   : skip (--skip-rom)\n'
    else
        printf '  ROM package   : %s\n' "$ROM_SHOWN"
    fi
    if [ "$has_app_repo" = 0 ]; then
        printf '  APK           : skip (no app repo)\n'
    elif [ "$SKIP_APK" = 1 ]; then
        printf '  APK           : skip (--skip-apk)\n'
    else
        printf '  APK           : %s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    fi
    printf '  nothing was executed (plan-only).\n'
    exit 0
fi

# ---------------------------------------------------------------- 1. image ----
if [ "$FORCE" = 1 ] || [ ! -f downloads/manifest.json ]; then
    step "fetch redroid 15 arm64 image (resumable, verifies every layer digest)"
    "$PY" scripts/fetch-redroid15.py
else
    step "image already downloaded (downloads/manifest.json)"
fi

# ---------------------------------------------------------------- 2. rootfs ---
if [ "$FORCE" = 1 ] || [ ! -f rootfs-index.json ]; then
    step "extract rootfs"
    "$PY" scripts/extract-rootfs.py
else
    step "rootfs already extracted (rootfs-index.json)"
fi

# ----------------------------------------------------------------- 3. APEX ----
# The .expanded markers are not enough to skip this step: rootfs-index.json is
# written by two different scripts (extract-rootfs.py rebuilds it from the image
# layers, extract-apex.py appends the expanded payloads), so a cache that keeps
# rootfs/apex but loses rootfs-index.json leaves the index without any APEX
# contents. Skipping on the marker alone then made prepare-a15.py fail with
#   Missing Android 15 program dalvikvm64 at /apex/com.android.art/bin/dalvikvm64
# (CI, 2026-10-30). Treat "the index does not mention the ART APEX" as "not
# expanded": extract-apex.py re-indexes an already-expanded tree in place.
apex_indexed() {
    [ -f rootfs-index.json ] && grep -q '"apex/com.android.art/bin/dalvikvm64"' rootfs-index.json
}
if [ "$FORCE" = 1 ] || [ ! -f rootfs/apex/com.android.art/.expanded ] || ! apex_indexed; then
    step "expand APEX payloads"
    "$PY" scripts/extract-apex.py
else
    step "APEX already expanded"
fi

# -------------------------------------------------------------- 4. sources ----
if [ "$FORCE" = 1 ] || [ ! -d runtime/upstream/bionic ]; then
    step "fetch API 35 sources (bionic / system/core / liblog / zlib ...)"
    sh runtime/fetch-a15-sources.sh
else
    step "API 35 sources already present (runtime/upstream)"
fi

# -------------------------------------------------------------- 5. runtime ----
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/liba15vm-bootstrap.a ]; then
    step "static bionic"
    sh runtime/build-static.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/bin/source-linker64 ]; then
    step "private linker"
    sh runtime/build-linker.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/libc.so ] || [ ! -f runtime/build/lib/libdl.so ]; then
    step "libc + libdl"
    sh runtime/build-libc.sh
fi
if [ "$FORCE" = 1 ] || [ ! -f runtime/build/lib/libm.so ]; then
    step "libm"
    sh runtime/build-libm.sh
fi

# ------------------------------------------------- 6. host GPU endpoint --------
# prepare-a15.py needs bridges/bin/desktop-gpu-host. It is the host-side GPU
# endpoint (API-independent, shared by A9/A10/A15). By default the pinned copy
# under prebuilt/arm64 is installed (sha256-checked). A15_REBUILD_DESKTOP=1
# rebuilds it from the sources vendored in third_party/graphics, without the A10
# container pipeline (needs NDK 29 and the fetched graphics upstream).
if [ "${A15_REBUILD_DESKTOP:-0}" = 1 ]; then
    step "desktop GPU endpoint (rebuild from vendored sources)"
    [ -d third_party/graphics/a9/upstream/goldfish ] || sh scripts/fetch-graphics-sources.sh
    sh scripts/build-desktop-host.sh
else
    step "desktop GPU endpoint (pinned)"
    sh scripts/install-desktop-host.sh
fi

# --------------------------------------------------------------- 7. bridges ---
# Ordered: property/binder/devices/log are link inputs of the JNI bridge; the
# graphics layer provides the HAL modules prepare-a15.py stages.
bridge_product() {
    case "$1" in
        property)      printf '%s' bridges/lib/liba15vm-property-client.so ;;
        binder)        printf '%s' bridges/lib/liba15binder.so ;;
        devices)       printf '%s' bridges/lib/liba15vm-guest-devices.so ;;
        guest-log)     printf '%s' bridges/lib/liba15vm-guest-log.so ;;
        jni-register)  printf '%s' bridges/lib/liba15vm-jni-register.so ;;
        host-hal)      printf '%s' bridges/bin/host-devices ;;
        graphics-a15)  printf '%s' bridges/graphics/lib/libGLESv2_emulation.so ;;
        # The project's legacy audio HAL replaces the image's silent stub.
        audio-hal)     printf '%s' bridges/lib/audio.primary.default.so ;;
    esac
}
for bridge in property binder devices guest-log jni-register host-hal graphics-a15 audio-hal; do
    product=$(bridge_product "$bridge")
    if [ "$FORCE" = 1 ] || [ ! -f "$product" ]; then
        if [ "$bridge" = graphics-a15 ] && [ ! -d third_party/graphics/a9/upstream/goldfish ]; then
            step "fetch pinned graphics sources"
            sh scripts/fetch-graphics-sources.sh
        fi
        step "bridge: $bridge"
        sh "bridges/build-$bridge.sh"
    fi
done

# ------------------------------------------------ 7. desktop window renderer ---
# The host-side renderer (liba15vm-desktop-window.so) decodes the EGL/GLES stream the
# guest sends, and GLES1 support only exists in third_party/graphics/container
# (90cb89e's skip/ES1 replies, wired into the build by 7f217bd). Its canonical output is
# artifacts/native-display, produced by scripts/build-desktop-window.sh — never a
# pinned binary and never the copy unpacked from _bin/A10VM.apk.
# Gradle owns the same decision (:native-display:buildDesktopWindowRenderer) so that an
# Android Studio build cannot silently package the A10 renderer either; the explicit
# -PnativeDisplayPrepared below only pins this directory.
step "desktop window renderer (from source)"
sh scripts/build-desktop-window.sh

# ---------------------------------------------------- 7. assets + prepared -----
step "boot animation asset"
"$PY" scripts/make-bootanimation.py

step "prepared (manifest + assets + jniLibs)"
"$PY" scripts/prepare-a15.py

# --------------------------------------------------------- 8. ROM package ------
# The ROM package is this repository's own product (rom/pack-rom.py -> dist/).
# It only depends on prepared: no Gradle, no APK, no app sources. Third-party
# ROM authors stop here (--skip-apk) and never need the app repository.
if [ "$SKIP_ROM" = 1 ]; then
    step "skip-rom: runtime directory only (prepared), no ROM zip"
else
    step "ROM package (rom/pack-rom.py -> dist/)"
    set -- --prepared prepared
    if [ -n "$ROM_VERSION" ]; then set -- "$@" --rom-version "$ROM_VERSION"; fi
    if [ -n "$LIB_MODE" ]; then set -- "$@" --lib-mode "$LIB_MODE"; fi
    if [ -n "$ROM_OUT" ]; then set -- "$@" --out "$ROM_OUT"; fi
    "$PY" rom/pack-rom.py "$@"
fi

# ----------------------------------------------------------------- 9. APK -----
# The APK (and the host desktop renderer) belong to the **app repository**.
# A third-party clone of THIS repository alone must stop cleanly after the ROM
# package instead of failing later with "gradlew: not found".
if [ "$has_app_repo" = 0 ]; then
    step "no app repository found at $ROOT"
    echo "  ROM-only build: this repository produces the runtime tree and the ROM package."
    echo "  Missing $ROOT/gradlew[.bat] or $ROOT/settings.gradle.kts"
    echo "  APK + host renderer live in the app repository (docs/rom-package/11 §2)."
    if [ "$SKIP_ROM" = 0 ]; then echo "  ROM package: $ROM_SHOWN"; else echo "  Runtime tree: $(pwd)/prepared (--skip-rom)"; fi
    echo "  Done (no APK)."
    exit 0
fi
if [ "$SKIP_APK" = 1 ]; then
    step "skip-apk: stopping before Gradle"
    if [ "$SKIP_ROM" = 0 ]; then echo "  ROM package: $ROM_SHOWN"; else echo "  Runtime tree: $(pwd)/prepared (--skip-rom)"; fi
    exit 0
fi
# 客体 .so 不进 APK 的 lib/arm64-v8a（docs/rom-package/04 §4）：旧格式 payload 在
# assets/guest-runtime-a15/lib/**（清单 files[].libPath），Gradle 消费 prepared-apk。
step "APK payload (rom/stage-apk-payload.py -> prepared-apk/)"
"$PY" rom/stage-apk-payload.py --prepared prepared --out prepared-apk
step "APK (app-debug.apk)"
cd "$ROOT"
if [ -x ./gradlew ]; then
    ./gradlew :app:assembleDebug -PnativeDisplayPrepared=guest-runtime-a15/artifacts/native-display
else
    sh ./gradlew :app:assembleDebug -PnativeDisplayPrepared=guest-runtime-a15/artifacts/native-display
fi
printf '\nA15 pipeline complete: %s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"