#!/bin/sh
# Fetch the pinned Android 15.0.0 sources used to build the A15 guest loader.
#
# The tag is immutable; the extracted file hashes are recorded by the build
# scripts. A15 moved several components out of system/core relative to A9/A10:
#   * liblog lives in system/logging/liblog (not system/core/liblog);
#   * the allocator is external/jemalloc_new;
#   * the property info parser lives in system/libpropertyinfoparser.
# The list below follows the Android 15 tree; a missing optional archive is a
# warning, a missing bionic/system-core archive is fatal.
set -eu
: "${MODULE:=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RT="$MODULE/runtime"
UP="$RT/upstream"
# The guest image is Redroid 15.0.0_64only-latest, whose /system/build.prop says
#   ro.build.id=BP1A.250505.005.D1
# AOSP's build-number table maps BP1A.250505.005.D1 to android-15.0.0_r36, so the
# runtime sources are pinned to that tag. Using r1 here would build an API 35
# libc from a 2024-09 tree while the image is 2025-05: the ABI is the same, but
# the sources (and therefore the closure evidence) would not match the guest.
TAG=${A15_TAG:-refs/tags/android-15.0.0_r36}
BASE="https://android.googlesource.com/platform"

mkdir -p "$RT/downloads" "$UP"

fetch() {
    repo="$1"; sub="$2"; dest="$3"; optional="${4:-}"
    if [ -d "$UP/$dest" ] && [ -n "$(ls -A "$UP/$dest" 2>/dev/null)" ]; then return 0; fi
    name=$(printf '%s-%s' "$repo" "$sub" | tr / _)
    archive="$RT/downloads/$name.tar.gz"
    if [ ! -s "$archive" ]; then
        if [ "$sub" = "." ]; then url="$BASE/$repo/+archive/$TAG.tar.gz";
        else url="$BASE/$repo/+archive/$TAG/$sub.tar.gz"; fi
        printf 'fetch %s\n' "$url"
        if ! curl -sS --fail --retry 3 --retry-delay 10 --max-time 900 "$url" -o "$archive.part"; then
            if [ -n "$optional" ]; then
                printf 'warning: optional archive unavailable: %s\n' "$url" >&2
                rm -f "$archive.part"
                return 0
            fi
            printf 'error: required archive unavailable: %s\n' "$url" >&2
            exit 1
        fi
        mv "$archive.part" "$archive"
    fi
    mkdir -p "$UP/$dest"
    # AOSP archives contain host-only symlinks that Windows cannot create.
    tar -mxzf "$archive" -C "$UP/$dest" 2>/dev/null || true
}

fetch_blob() {
    repo="$1"; sub="$2"; dest="$3"
    if [ -s "$UP/$dest" ]; then return 0; fi
    mkdir -p "$(dirname "$UP/$dest")"
    url="$BASE/$repo/+/$TAG/$sub?format=TEXT"
    printf 'fetch %s\n' "$url"
    curl -sS --fail --retry 3 --retry-delay 3 --max-time 300 "$url" -o "$UP/$dest.b64"
    base64 -d "$UP/$dest.b64" > "$UP/$dest"
    rm -f "$UP/$dest.b64"
}

# Runtime closure. bionic and system/core are required.
#
# Whole-repository archives are used on purpose: googlesource returns HTTP 400
# for some subdirectory archives (`system/core/base` on android-15.0.0_r1 is one),
# while the repository archive is a stable 200. A failed subdirectory fetch used
# to abort the whole stage after a partial download.
fetch bionic                         .                    bionic
fetch system/core                    .                    system/core
fetch system/logging                 .                    system/logging

# Modules parsed by runtime/select-libc-sources.py. Android 15 describes libc
# through external defaults (gwp_asan, scudo, llvm-libc, the unwinder and the
# optimized math routines); without these archives the selector stops with
#   missing blueprint: external/<name>/Android.bp
# even though bionic itself downloaded fine.
fetch external/arm-optimized-routines .                    external/arm-optimized-routines
fetch external/gwp_asan               .                    external/gwp_asan
fetch external/scudo                  .                    external/scudo
fetch external/llvm-libc              .                    external/llvm-libc
fetch external/libunwind_llvm         .                    external/libunwind_llvm

# C++ runtime and allocator used by ART and by the guest's own C++ programs.
fetch external/libcxx                src                  external/libcxx/src
fetch external/libcxx                include              external/libcxx/include
fetch external/libcxxabi             src                  external/libcxxabi/src
fetch external/libcxxabi             include              external/libcxxabi/include
fetch external/zlib                  .                    external/zlib
fetch external/jemalloc_new          .                    external/jemalloc_new

# The exact Android.bp files are what the source selectors parse. The
# whole-repository archives above already contain them, so nothing else is
# fetched here: a separate blob fetch was returning 404 for Android 15 and
# aborting the stage after a successful download.

echo "sources ready under $UP"
