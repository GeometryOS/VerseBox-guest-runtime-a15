# vendor/hidl — the interface declarations this domain builds against

A15's HAL client stacks are compiled against generated AIDL/HIDL declarations.
The two kinds of file here have different provenance and must not be confused:

| Path | Provenance | Role |
| --- | --- | --- |
| `aidl/android/hardware/vibrator/*.aidl` | AOSP `android-15.0.0_r36`, `hardware/interfaces/vibrator/aidl` | interface source, compiled by `aidl` into build intermediates |
| `aidl/android/os/PersistableBundle.aidl` | **project-authored declaration** | AOSP exposes this parcelable as the prebuilt `PersistableBundle_aidl` header module; `frameworks/base` is not in this workspace, so the declaration is kept here. The project never marshals it (`performVendorEffect` is UNSUPPORTED_OPERATION) |
| `ndk/include_ndk`, `ndk/include_cpp`, `ndk/include_platform` | AOSP `android-15.0.0_r36`, `frameworks/native/libs/binder/ndk` | AIDL/Binder **C++** NDK headers (`ndk::ScopedAStatus`, `ndk::BnCInterface`, `AServiceManager_addService`, `ABinderProcess_*`) |

## Why the binder headers are vendored

The NDK 29 sysroot ships only the C surface:
`android/binder_ibinder.h`, `binder_parcel.h`, `binder_status.h` (+ JNI).
The C++ wrappers and the servicemanager/process entry points are platform
headers in `frameworks/native` and are **not** part of the NDK. AIDL's
`--lang=ndk` output includes `<android/binder_interface_utils.h>`, so they are
required to compile any generated interface at all.

They are pinned to the same AOSP tag as the rest of this domain and listed in
`sources.sha256`, the same discipline `vendor/runtime/dynamic/sources.sha256`
uses for bionic.

## Regenerating

    sh guest-runtime-a15/vendor/hidl/fetch-sources.sh

The script re-downloads both subtrees at the pinned tag, extracts them over
`aidl/android/hardware/vibrator` and `ndk/`, then verifies `sources.sha256`
(the project-authored `PersistableBundle.aidl` is not touched). Nothing under
`bridges/generated/aidl` is checked in: that directory is `aidl` output and is
rebuilt by `bridges/build-host-hal.sh`.
