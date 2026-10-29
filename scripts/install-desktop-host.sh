#!/bin/sh
# Install the pinned desktop-gpu-host (host GPU endpoint) into bridges/bin.
#
# Why pinned instead of rebuilt here: desktop-gpu-host is the host-side endpoint
# (it runs in the app process and speaks the desktop GPU protocol to the guest).
# Its ABI does not depend on the guest API level, so A9/A10/A15 share it.
# Rebuilding it needs the A10 container graphics pipeline
# (third_party/graphics/container/build-desktop.sh + build-desktop-host.sh plus
# the A10 runtime tree); those scripts are vendored in this repository, but the
# A10 runtime tree is not. prepare-a15.py needs the binary, so the repository
# pins it with a sha256 and this script installs it.
#
# Usage: sh scripts/install-desktop-host.sh
set -eu
MODULE=${MODULE:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
SRC="$MODULE/prebuilt/arm64/desktop-gpu-host"
SUMS="$MODULE/prebuilt/arm64/desktop-gpu-host.sha256"
DST="$MODULE/bridges/bin/desktop-gpu-host"
test -f "$SRC" || { echo "install-desktop-host: missing $SRC" >&2; exit 1; }
test -f "$SUMS" || { echo "install-desktop-host: missing $SUMS" >&2; exit 1; }
expected=$(tr -d '[:space:]' < "$SUMS" | tr 'A-Z' 'a-z')
actual=$(sha256sum "$SRC" | cut -d ' ' -f 1)
if [ "$expected" != "$actual" ]; then
    echo "install-desktop-host: sha256 mismatch for $SRC" >&2
    echo "  expected $expected" >&2
    echo "  actual   $actual" >&2
    exit 1
fi
mkdir -p "$MODULE/bridges/bin"
if [ -f "$DST" ] && [ "$(sha256sum "$DST" | cut -d ' ' -f 1)" = "$expected" ]; then
    echo "desktop-gpu-host: up to date ($actual)"
    exit 0
fi
cp "$SRC" "$DST"
chmod 755 "$DST"
echo "desktop-gpu-host: installed ($actual)"
