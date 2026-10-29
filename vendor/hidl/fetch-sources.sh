#!/bin/sh
# Re-download the AIDL declarations and binder NDK headers at the pinned tag.
# Same shape as vendor/runtime/dynamic/fetch-sources.sh: download to a temp
# archive, extract, then verify sources.sha256.
set -eu
HIDL=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TAG=android-15.0.0_r36
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
for tool in curl tar sha256sum; do command -v "$tool" >/dev/null; done

fetch() {
    repository=$1
    subtree=$2
    destination=$3
    url="https://android.googlesource.com/platform/$repository/+archive/$TAG/$subtree.tar.gz"
    archive="$WORK/$(printf '%s-%s' "$repository" "$subtree" | tr '/' '_').tar.gz"
    curl --fail --silent --show-error --max-time 120 --retry 1 --retry-delay 10 \
        "$url" -o "$archive"
    mkdir -p "$HIDL/$destination"
    tar -xzf "$archive" -C "$HIDL/$destination"
}

fetch hardware/interfaces vibrator/aidl aidl
fetch hardware/interfaces sensors/aidl aidl
fetch hardware/interfaces thermal/aidl aidl
fetch hardware/interfaces common/aidl aidl
fetch hardware/interfaces common/fmq/aidl aidl
fetch frameworks/native libs/binder/ndk ndk

cd "$HIDL"
# PersistableBundle.aidl is project-authored (see README.md); the check covers
# it too because its content must not drift silently.
sha256sum --check --status sources.sha256
echo "vendor/hidl: sources verified against sources.sha256"
