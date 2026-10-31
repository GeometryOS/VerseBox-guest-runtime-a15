#!/usr/bin/env python3
"""Build the Android 15 (API 35, arm64-only) prepared runtime.

Inputs
    rootfs/            extracted Redroid 15 layer (regular files only)
    rootfs/apex/       APEX payloads expanded by scripts/extract-apex.py
    rootfs-index.json  every image entry with type/mode/link target
    runtime/build/     API 35 linker/libc/libdl/libm built from AOSP 15 sources
    bridges/lib/       project bridges rebuilt for API 35 (stage 2)
    vendor/framework/  shared framework classes (API 35 branch pending)

Output
    prepared/assets/    guest image files + DEX + properties
    prepared/jniLibs/   native payloads addressed by the runtime manifest

Differences from prepare-a9.py that matter:
  * Android 15 keeps libc/libdl/libm/linker inside
    /apex/com.android.runtime/{bin,lib64/bionic}, and ART inside
    /apex/com.android.art. `/system/lib64/libc.so` is only a symlink.
  * The boot class path comes from protobuf files
    (/apex/com.android.art/etc/classpaths/bootclasspath.pb), not init.environ.rc.
  * The image is 64-bit only: any 32-bit ELF is a hard failure, not a skip.
  * The image's own libs are 16 KiB page aligned; the runtime is linked with
    -z max-page-size=16384 and that must stay consistent with what is packaged.
"""
import hashlib
import json
import os
import posixpath
import re
import shutil
import stat
import subprocess
import sys
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
A15 = MODULE
TREE = A15 / "rootfs"
INDEX = A15 / "rootfs-index.json"
APEX_TREE = TREE / "apex"
RUNTIME = A15 / "runtime" / "build"
BRIDGES = A15 / "bridges"
OUT = A15 / "prepared"
ASSET_PREFIX = "guest-runtime-a15/"
# Host toolchain layout: Git Bash/MSYS uses the windows-x86_64 prebuilt tree and
# .exe/.bat wrappers; Linux/CI uses linux-x86_64 and the plain executables.
HOST_TAG = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
NDK = Path(os.environ.get("ANDROID_NDK_HOME",
    r"C:/Users/22437/AppData/Local/Android/Sdk/ndk/29.0.14206865"
    if os.name == "nt" else str(Path.home() / "Android/Sdk/ndk/29.0.14206865")))
READELF = NDK / "toolchains" / "llvm" / "prebuilt" / HOST_TAG / "bin" / (
    "llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
SDK = Path(os.environ.get("ANDROID_HOME",
    r"C:/Users/22437/AppData/Local/Android/Sdk" if os.name == "nt"
    else str(Path.home() / "Android/Sdk")))
ANDROID_JAR = SDK / "platforms" / "android-35" / "android.jar"
D8_VERSION = "35.0.0"


def d8_binary(windows=None):
    """SDK d8 entry point for this platform (d8.bat on Windows, d8 elsewhere)."""
    if windows is None:
        windows = os.name == "nt"
    return SDK / "build-tools" / D8_VERSION / ("d8.bat" if windows else "d8")


D8 = d8_binary()

# Guest runtime destinations. A15 /system/{bin/linker64,lib64/libc.so,...} are
# symlinks into these APEX paths, so the source-built runtime replaces the APEX
# files and the ROM symlinks keep pointing at them.
APEX_RUNTIME = "apex/com.android.runtime"
APEX_ART = "apex/com.android.art"
APEX_STATSD = "apex/com.android.os.statsd"
RUNTIME_FILES = [
    ("linker64", RUNTIME / "bin" / "source-linker64", "liba15vm-source-linker.so",
     "linker64", "/" + APEX_RUNTIME + "/bin/linker64"),
    # The host executes the guest through this logical: `ld-android.so` IS the
    # source linker (A9 registers it the same way). Without it
    # GuestRuntime.checkJava cannot start dalvikvm64 at all.
    ("ld-android.so", RUNTIME / "bin" / "source-linker64", "liba15vm-guest-ld-android.so",
     "ld-android.so", "/" + APEX_RUNTIME + "/lib64/ld-android.so"),
    ("libc.so", RUNTIME / "lib" / "libc.so", "liba15vm-guest-libc.so",
     "libc.so", "/" + APEX_RUNTIME + "/lib64/bionic/libc.so"),
    ("libdl.so", RUNTIME / "lib" / "libdl.so", "liba15vm-guest-libdl.so",
     "libdl.so", "/" + APEX_RUNTIME + "/lib64/bionic/libdl.so"),
    ("libm.so", RUNTIME / "lib" / "libm.so", "liba15vm-guest-libm.so",
     "libm.so", "/" + APEX_RUNTIME + "/lib64/bionic/libm.so"),
]

# ROM companion: A15's libc++.so is still self-contained (NEEDED only libc,
# libm, libdl) and there is no libunwind.so in the image.
COMPANION_FILES = [
    ("libc++.so", TREE / "system" / "lib64" / "libc++.so", "liba15vm-guest-libc++.so",
     "libc++.so"),
]

# Project bridges, rebuilt for API 35 in stage 2. They are listed so a missing
# build is reported instead of silently omitted.
BRIDGE_FILES = [
    ("liba15vm-property-client.so", BRIDGES / "lib" / "liba15vm-property-client.so",
     "liba15vm-property-client.so", "liba15vm-property-client.so", None),
    ("liba15binder.so", BRIDGES / "lib" / "liba15binder.so",
     "liba15binder.so", "liba15binder.so", None),
    ("liba15vm-binder-devices.so", BRIDGES / "lib" / "liba15vm-binder-devices.so",
     "liba15vm-binder-devices.so", "liba15vm-binder-devices.so", None),
    ("liba15vm-guest-devices.so", BRIDGES / "lib" / "liba15vm-guest-devices.so",
     "liba15vm-guest-devices.so", "liba15vm-guest-devices.so", None),
    ("liba15vm-guest-log.so", BRIDGES / "lib" / "liba15vm-guest-log.so",
     "liba15vm-guest-log.so", "liba15vm-guest-log.so", None),
    ("liba15vm-manager-policy.so", BRIDGES / "lib" / "liba15vm-manager-policy.so",
     "liba15vm-manager-policy.so", "liba15vm-manager-policy.so", None),
    # dalvikvm64 does not run AndroidRuntime::startReg; this bridge's JNI_OnLoad
    # calls the exported android::AndroidRuntime::startReg(JNIEnv*) so the guest
    # JNI table (SystemProperties, Build, ...) is populated for the server role.
    ("liba15vm-jni-register.so", BRIDGES / "lib" / "liba15vm-jni-register.so",
     "liba15vm-jni-register.so", "liba15vm-jni-register.so", None),
]

# Guest paths the project graphics modules must be installed to. The image's
# composer@2.1 / allocator@2.0 services load them through hw_get_module(), which
# honours ro.hardware.egl/gralloc/hwcomposer (guest-a15.prop sets
# egl=emulation, gralloc=ranchu, hwcomposer=ranchu). Same layout as A9/A10.
GRAPHICS_GUEST_PATHS = {
    "libEGL_emulation.so": "/vendor/lib64/egl/libEGL_emulation.so",
    "libGLESv1_CM_emulation.so": "/vendor/lib64/egl/libGLESv1_CM_emulation.so",
    "libGLESv2_emulation.so": "/vendor/lib64/egl/libGLESv2_emulation.so",
    "gralloc.ranchu.so": "/vendor/lib64/hw/gralloc.ranchu.so",
    "hwcomposer.ranchu.so": "/vendor/lib64/hw/hwcomposer.ranchu.so",
}

GRAPHICS_FILES = [
    ("libOpenglSystemCommon.so", "libOpenglSystemCommon.so", None),
    ("libEGL_emulation.so", "libEGL_emulation.so", GRAPHICS_GUEST_PATHS["libEGL_emulation.so"]),
    ("libGLESv1_CM_emulation.so", "libGLESv1_CM_emulation.so",
     GRAPHICS_GUEST_PATHS["libGLESv1_CM_emulation.so"]),
    ("libGLESv2_emulation.so", "libGLESv2_emulation.so",
     GRAPHICS_GUEST_PATHS["libGLESv2_emulation.so"]),
    ("gralloc.ranchu.so", "gralloc.ranchu.so", GRAPHICS_GUEST_PATHS["gralloc.ranchu.so"]),
    ("hwcomposer.ranchu.so", "hwcomposer.ranchu.so",
     GRAPHICS_GUEST_PATHS["hwcomposer.ranchu.so"]),
    ("liba15vm-hal-modules.so", "liba15vm-hal-modules.so", None),
]

PROGRAM_FILES = [
    ("property-service", BRIDGES / "bin" / "property-service", "liba15vm-property-service.so"),
    ("seed-properties", BRIDGES / "bin" / "seed-properties", "liba15vm-seed-properties.so"),
    ("binder-broker", BRIDGES / "bin" / "binder-broker", "liba15vm-binder-broker.so"),
    # Stage-5 gate 6: the host GPU endpoint. Project binary, not guest code: it
    # links the device's EGL/GLES and speaks the desktop protocol over a socket,
    # so it is API-independent. The staged copy is the A10 build (same sources in
    # container-probe/graphics); STATUS.md records that it must be rebuilt from
    # those sources if it ever needs a change.
    ("desktop-gpu-host", BRIDGES / "bin" / "desktop-gpu-host", "liba15vm-desktop-gpu-host.so"),
    # Round 72: the "host-devices" program the host already launches (and already
    # logs as framework-host-devices.log, awaiting "host_devices_ready=1") never
    # had an A15 build, so every A15 start recorded
    #   WARN host-devices unavailable: Missing runtime input: host-devices
    # and android.hardware.vibrator had no service at all. The AIDL vibrator HAL
    # in vendor/native/HostDevicesA15.cpp is that program. It is a project
    # binary, not a ROM binary, because the image ships the vibrator client
    # stack and the legacy HMI module but no vibrator service.
    ("host-devices", BRIDGES / "bin" / "host-devices", "liba15vm-host-devices.so"),
]

PLATFORM_LIBRARIES = {
    "propertyClient": "liba15vm-property-client.so",
    "guestDevices": "liba15vm-guest-devices.so",
    "binderDevices": "liba15vm-binder-devices.so",
    "guestLog": "liba15vm-guest-log.so",
    "managerPolicy": "liba15vm-manager-policy.so",
    "binderCore": "liba15binder.so",
    # JNI table registration for the dalvikvm64-launched server (see BRIDGE_FILES).
    "jniRegister": "liba15vm-jni-register.so",
    # halModules is produced with the graphics stage; keep the role declared so
    # the manifest schema stays identical to A9/A10.
    "halModules": "liba15vm-hal-modules.so",
}

# Guest programs the host launches by logical role.
A15_PROGRAMS = {
    "dalvikvm64": APEX_ART + "/bin/dalvikvm64",
    "app_process64": "system/bin/app_process64",
    "servicemanager": "system/bin/servicemanager",
    # The HIDL service manager is NOT absent from the A15 image: it lives in
    # system_ext (system/bin/hwservicemanager is a symlink to it). The earlier
    # "A15 has no hwservicemanager" conclusion came from listing system/bin only
    # and is wrong; the real-device log proves the need: composer/allocator/
    # surfaceflinger all open /dev/hwbinder and terminate without it.
    "hwservicemanager": "system/system_ext/bin/hwservicemanager",
    "surfaceflinger": "system/bin/surfaceflinger",
    # Round 87 (UI-0): the guest's own boot animation. On a device init starts
    # /system/bin/bootanimation (system/etc/init/bootanim.rc) before system_server
    # and stops it when boot completes; this runtime has no init, so the host
    # launches the image binary right after SurfaceFlinger and stops it when the
    # desktop check passes. Redroid ships no /system/media/bootanimation.zip, so
    # scripts/make-bootanimation.py generates the asset that is staged below.
    "bootanimation": "system/bin/bootanimation",
    "installd": "system/bin/installd",
    # Round 78: LockSettingsService.onThirdPartyAppsStarted() runs near the end
    # of startOtherServices (SystemServer.java:3440) and migrates user 0 to an
    # SP-based credential. That path calls
    # SyntheticPasswordCrypto.createBlob -> KeyStore2.getService, which needs the
    # AIDL service android.system.keystore2.IKeystoreService/default:
    #   java.lang.IllegalStateException: Could not connect to Keystore service.
    #   Keystore may have crashed or not been initialized
    # (instance 1793269336507, 2026-10-29 18:25)
    # The image ships both halves: system/bin/keystore2 (VINTF
    # android.system.keystore2-service.xml) and the HIDL software backend
    # vendor/bin/hw/android.hardware.keymaster@4.1-service (manifest
    # android.hardware.keymaster@4.1-service.xml, libpuresoftkeymasterdevice.so).
    # init starts keymaster first (early_hal), then keystore2; the profile services
    # below keep that order. uid/gid are from the image's rc files:
    #   keymaster: user system / group system            -> 1000/1000
    #   keystore2: user keystore / group keystore ...    -> 1017/1017
    "keystore2": "system/bin/keystore2",
    "keymaster": "vendor/bin/hw/android.hardware.keymaster@4.1-service",
    # Round 75 (B track): the framework blocks in AudioService's constructor and
    # nothing in this runtime ever started the audio stack:
    #   SystemServerTiming: StartAudioService                      <- no "took to complete"
    #   AudioSystem-JNI: Command failed for android_media_AudioSystem_checkAudioFlinger: -32
    #   AS.AudioService: Audioserver died.
    #   Watchdog: Blocked in handler on main thread (main) for 65s
    #     at android.media.AudioSystem.isCallScreeningModeSupported(Native Method)
    #     at com.android.server.audio.AudioService.<init>(AudioService.java:1490)
    # -32 is DEAD_OBJECT: no process registered "media.audio_flinger". init starts
    # audioserver on a real device; this runtime has no init, so the host launches
    # the ROM binary with the same shape as artd/sensorservice (see
    # runtimeProfile.services, where audioserver is registered as a service and
    # *before* SystemServer starts). The image ships the binary
    # (2,881,912 B) plus its init rc (system/etc/init/audioserver.rc).
    "audioserver": "system/bin/audioserver",
    # Round 89 (storage + media): MediaProvider's bindApplication never finishes
    # because libcodec2 asks HIDL for android.hardware.media.c2@1.0::
    # IComponentStore/software and libhidlbase retries that lookup forever
    # (getRawServiceInternal only bounds the retry counter at INT_MAX; the only
    # non-retry exit is the canCastInterface failure path). Real devices have
    # /apex/com.android.media.swcodec/bin/mediaswcodec for exactly this name and
    # this image declares it in system/etc/vintf/manifest/
    # manifest_media_c2_software.xml (hidl @1.2 + aidl IComponentStore/software),
    # but nothing in this runtime ever started the binary, so the declared HAL
    # had no runtime. A10 has always launched it (guest-runtime/media_bundle.py
    # "media-codec"); A15 never did.
    #   鐪熸満 (instance 1793301644648):
    #     GUEST_LOG 5 HidlServiceManagement: Waited one second for
    #       android.hardware.media.c2@1.0::IComponentStore/software   (x2178)
    #     ActivityManager: Extending process start timeout by 3ms ...
    #     ActivityManager: ANR in com.android.providers.media.module
    #     Reason: Process ... failed to complete startup
    #     a15_application_exit=11074:com.android.providers.media.module code=137
    #     StorageManagerService: No primary storage defined yet; ...
    # i.e. no MediaProvider => no ExternalStorageService => the emulated volume is
    # dropped by StorageManagerService => Files/Gallery/screenshot all break.
    "media-codec": "apex/com.android.media.swcodec/bin/mediaswcodec",
    # Round 89: mediaserver publishes media.player / media.codec and -- the one
    # this round needed -- media.resource_manager (the Codec2 resource manager).
    # Without it the codec2 client inside MediaProvider blocks in
    #   ServiceManagerCppClient: Waited one second for media.resource_manager
    # and MediaProvider misses the 10 s provider-publish window:
    #   ActivityManager: Killing ...:com.android.providers.media.module (adj 0):
    #     timeout publishing content providers
    #   ActivityManager: Killing ...:com.android.gallery3d (adj 0):
    #     depends on provider com.android.providers.media.module/...
    # (instance 1793358671561, 19:13-19:18) -- exactly the "Gallery hangs / Files
    # app fails to load" symptom. A10 has always launched this binary
    # (guest-runtime/media_bundle.py "media"); A15 never did.
    "media": "system/bin/mediaserver",
    # Round 75: audioserver alone is not enough -- AudioFlinger reaches the HAL
    # through libaudiohal, whose HIDL provider asks hwservicemanager for
    # android.hardware.audio@7.0::IDevicesFactory/default. The image declares
    # exactly that (vendor/etc/vintf/manifest.xml: format hidl, transport
    # hwbinder, @7.0::IDevicesFactory/default) and ships the service binary
    # (vendor/bin/hw/android.hardware.audio.service, 51,440 B) which dlopens
    # android.hardware.audio@7.0-impl.so from vendor/lib64/hw and wraps the
    # legacy module audio.primary.default.so. This is the composer/allocator
    # shape (launch the ROM binary over the project's /dev/hwbinder), not the
    # A9/A10 host-audio daemon. init.rc: user audioserver / group audio, i.e.
    # AID_AUDIOSERVER=1041 / AID_AUDIO=1005.
    "audio-hal": "vendor/bin/hw/android.hardware.audio.service",
    "cameraserver": "system/bin/cameraserver",
    "dex2oat64": APEX_ART + "/bin/dex2oat64",
    # Stage-5 gate 8. The A15 image still carries HIDL 2.1 composer and HIDL 2.0
    # allocator service binaries (verified: vendor/bin/hw/...@2.1-service needs
    # libhwc2on1adapter + libhwc2onfbadapter and calls hw_get_module("hwcomposer");
    # the allocator impl calls hw_get_module("gralloc") and opens a gralloc0
    # device), so the host launches the ROM binaries exactly like A9 does. What
    # the project must supply is the module behind them (hwcomposer.ranchu /
    # gralloc.ranchu), not a replacement for the service binary.
    "graphics-composer": "vendor/bin/hw/android.hardware.graphics.composer@2.1-service",
    "graphics-allocator": "vendor/bin/hw/android.hardware.graphics.allocator@2.0-service",
    # PowerManagerService's libpowermanager (C++ NDK AIDL) blocks in
    # ServiceManagerCppClient::waitForService("android.hardware.power.IPower/default")
    # on every "Waited one second for android.hardware.power.IPower/default" line.
    # The A15 image ships the AIDL default implementation
    # (hardware/interfaces/power/aidl/default -> vendor/bin/hw/
    # android.hardware.power-service.example, 69,512 B) and its VINTF statement
    # (vendor/etc/vintf/manifest/power-default.xml, IPower/default version 5).
    # It is NDK binder, not HIDL: it registers on /dev/binder through libbinder_ndk,
    # i.e. through this project's broker + servicemanager, so the host launches the
    # ROM binary exactly like the graphics services. The Java LocalGuestService
    # route cannot stand in for it: the caller is C++ NDK AIDL, not Java Binder.
    "power": "vendor/bin/hw/android.hardware.power-service.example",
    # Round 46/47: after the power AIDL HAL registers, PowerManagerService moves on
    # to android.system.suspend.ISystemSuspend/default (9 wait lines in instance
    # 1793180080165). The image ships the framework HAL service binary
    # (system/bin/hw/android.system.suspend-service, 205,128 B) plus its VINTF
    # statement (system/etc/vintf/manifest/android.system.suspend-service.xml:
    # HIDL @1.0 + AIDL ISystemSuspend/default). It links libbinder (HIDL over
    # /dev/hwbinder) and libbinder_ndk (AIDL over /dev/binder); both endpoints are
    # project brokers, so the host launches the ROM binary like power/composer.
    "system-suspend": "system/bin/hw/android.system.suspend-service",
    # Round 64: BatteryService is the first startCoreServices entry and the guest dies
    # with exit 139 right after "SystemServiceManager: Starting
    # com.android.server.BatteryService" (instance 1793191802335, both runs). There is no
    # Java exception and no crash-handler line, which is the signature of a native fault
    # inside BatteryMonitor: it binds android.hardware.health.IHealth/default (AIDL) and
    # dereferences the resulting service. The image ships the AIDL default implementation
    # (vendor/bin/hw/android.hardware.health-service.example, 188,496 B) and its VINTF
    # statement (vendor/etc/vintf/manifest/android.hardware.health-service.example.xml,
    # <fqname>IHealth/default</fqname>), i.e. exactly the same shape as power/suspend:
    # host launches the ROM binary, it registers over this project's /dev/binder broker.
    "health": "vendor/bin/hw/android.hardware.health-service.example",
    # Round 71: SystemServer now dies with SIGABRT inside StartInputManagerService.
    # The last service-related line before the abort is
    #   ServiceManagerCppClient: Waiting for service 'stats' on '/dev/binder'...
    # and libinputflinger.so is linked against libstatspull.so, whose client waits
    # for the AIDL service "stats" (instance 1793209312957, 01:42).
    # The image ships the real statsd binary and its init.rc
    # (apex/com.android.os.statsd/etc/init.rc) says
    #   user statsd / group statsd log / socket statsdw
    # so this is the same launch-the-ROM-binary shape as power/system-suspend/health,
    # not a placeholder.
    "stats": APEX_STATSD + "/bin/statsd",
    # Round 72d: DeviceStateManagerService (right after StartInputManagerService)
    # blocks in ServiceManagerCppClient::waitForService("sensorservice") and the
    # image ships the sensorservice binary but nothing launches it: init does that
    # on a real device. The HAL it talks to is this project's AIDL
    # android.hardware.sensors.ISensors/default (HostDevicesA15.cpp), so the ROM
    # binary registers "sensorservice" instead of waiting for a HIDL HAL.
    "sensorservice": "system/bin/sensorservice",
    # Round 72f: UpdatePackagesIfNeeded (right after ArtManagerLocal) calls
    # ArtService.onBoot -> dexopt, and Android 15 executes dexopt in the artd
    # daemon: the main thread stops there while the log repeats
    #   ServiceManagerCppClient: Waited one second for artd
    # The ART APEX ships the binary (apex/com.android.art/bin/artd) and its init.rc
    # declares it a lazy AIDL service. There is no init here to start it on demand,
    # so the host runs it eagerly like power/health/stats. init.rc: user artd /
    # group artd with DAC_OVERRIDE DAC_READ_SEARCH FOWNER CHOWN; the project runs
    # it as uid 0 (the capability set is what root already implies here).
    "artd": APEX_ART + "/bin/artd",
    # Round 73: netd is deliberately NOT launched here (round 72g did, and it was
    # reverted). The ROM binary never reaches NetdNativeService::start():
    #   1. libnetd_updatable_init() waits for bpf.progs_loaded, which no process
    #      sets in this container (a real device gets it from bpfloader);
    #   2. forcing the property makes it open /sys/fs/bpf/netd_shared/... and the
    #      bpf(2) call is EPERM for the host app domain -> the library aborts
    #      (SIGABRT, caller libnetd_updatable.so).
    # netd's own main() treats libnetd_updatable_init() failure as non-fatal, but
    # the fatal check lives *inside* the library, so the binary cannot be used as
    # the AIDL "netd" provider here. The provider is the bootstrap dex endpoint
    # (A15Boot -> GuestNetworks.registerNetworkService), the same contract API 28
    # uses. See STATUS.md round 73 and docs/android15-port/14 搂7.
    #
    # apexd is deliberately NOT registered here.
    #
    # Round 43 ran it as a framework service and proved two things: it starts
    # in this runtime and reaches "AidlLazyServiceRegistrar: Registering service
    # apexservice" (vm/framework-apexd.log, 18 KB), but (a) the servicemanager
    # never sees that name, and (b) its real job -- mounting/verifying APEX
    # files -- cannot work in a container (it already fails with "Could not
    # create loop device" and "Activated 0 packages"). Solving mount inside the
    # container is not required by anything: the runtime only needs the *name*
    # "apexservice" to answer the queries SystemServer makes.
    #
    # The standalone runner _bin/a15/scratch/a15apexd.sh still exists, on
    # purpose: it is the cheap way to observe what real apexd does on its own
    # (never inside runtimeProfile.services, so it cannot damage /apex under a
    # live SystemServer).
    # "apexd": "system/bin/apexd",
    # No "graphics-configstore": the A15 image ships
    # android.hardware.configstore@1.1.so but no @1.1-service binary. Redroid 15
    # boots SurfaceFlinger that way, so the missing IConfigStore service is
    # tolerated by SF (defaults). See runtimeProfile.configstore.
}

# Directories whose contents are mounted at runtime instead of extracted.
SKIP_IMAGE_DIRS = ("dev", "proc", "sys", "data", "mnt", "acct", "cache", "config",
                   "d", "bugreports", "metadata", "storage", "sdcard", "tmp", "run",
                   "lost+found", "debug_ramdisk")
# Redroid keeps the compressed APEX archives too. The payload is already
# expanded under rootfs/apex/, and shipping ~500 MB of duplicate zips would only
# inflate the APK.
#
# B-track experiment (round 30, 2026-10-28): the image also ships prebuilt oat/vdex
# for the *system server class path* (`system/framework/oat/arm64/services.{art,odex,vdex}`,
# the `com.android.location.provider.*` triple and the APEX `service-art.jar@classes.*`
# triple). Every server log shows ART registering them
# (`Registering/Registered oat file 鈥?services.vdex`), and A15's release
# `OatDexFile::FindClassDef()` trusts an oat's *type lookup table* and does not fall back to
# the dex's own class_defs (runtime/oat/oat_file.cc 2340-2374). Those artifacts were produced
# against the image's full device class loader context, not against this runtime's
# (BOOTCLASSPATH 16 jars + a 3-jar SYSTEMSERVERCLASSPATH), so a class can be visible to
# `Class.forName` (which scans the loader's dex elements) yet invisible to the resolution
# `SystemServer.run()` performs through that oat's table. Leaving the triples out forces ART
# to read the dex inside the jar -- exactly the situation A9/A10 are in, and they work.
# Revert by deleting these three prefixes once the round-30 result is known.
SKIP_IMAGE_PREFIXES = ("system/apex/", "bootstrap-apex/",
                       "system/framework/oat/arm64/services.",
                       "system/framework/oat/arm64/com.android.location.provider.",
                       "system/framework/oat/arm64/apex@com.android.art@javalib@service-art.jar@classes.")

PROPERTY_SOURCES = [
    "/system/build.prop",
    "/system/etc/prop.default",
    "/vendor/build.prop",
    "/vendor/default.prop",
    "/odm/etc/build.prop",
    "/product/build.prop",
    "/system_ext/build.prop",
]


# Android 15's linker has no A10VM_ROOTFS path translation: search paths in
# ld.config.txt are host paths under the instance directory. A symlink stored
# as "/apex/..." would resolve against the host's /apex and break, so absolute
# targets inside the image are stored relative to the link directory.
ROOTFS_TOP = {"system", "apex", "vendor", "product", "odm", "etc", "data",
              "dev", "proc", "sys", "storage", "mnt", "acct", "cache",
              "config", "metadata", "lost+found"}


def host_symlink_target(path, target):
    if not target.startswith("/"):
        return target
    stripped = target.lstrip("/")
    if stripped.split("/", 1)[0] not in ROOTFS_TOP:
        return target
    return posixpath.relpath(stripped, posixpath.dirname(path) or ".")


def digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(4 << 20), b""):
            value.update(block)
    return value.hexdigest()


def elf_kind(path):
    with open(path, "rb") as stream:
        header = stream.read(20)
    if header[:4] != b"\x7fELF":
        return None
    if header[4] == 2 and header[18:20] == b"\xb7\x00":
        return "arm64"
    return "other"


def elf_align(path):
    with open(path, "rb") as stream:
        header = stream.read(64)
        phoff = int.from_bytes(header[32:40], "little")
        phentsize = int.from_bytes(header[54:56], "little")
        phnum = int.from_bytes(header[56:58], "little")
        aligns = set()
        for index in range(phnum):
            stream.seek(phoff + index * phentsize)
            entry = stream.read(phentsize)
            if len(entry) >= 56:
                aligns.add(int.from_bytes(entry[48:56], "little"))
    return aligns


def sanitize(name):
    return name.replace("/", "-").replace("@", "_")


def excluded(path):
    if any(path.startswith(prefix) for prefix in SKIP_IMAGE_PREFIXES):
        return True
    # APEX expand markers and ext4 lost+found are host-side bookkeeping. They
    # must never reach prepared/: aapt drops dotfiles from assets, so a manifest
    # entry for them would fail at installData() with a path-only exception.
    if path.endswith("/.expanded") or path == ".expanded":
        return True
    if path.endswith("/lost+found") or path == "lost+found" or "/lost+found/" in path:
        return True
    top = path.split("/", 1)[0]
    if top in SKIP_IMAGE_DIRS:
        return True
    # A15 64-only: 32-bit lib directories should not exist. If they ever do,
    # prepare must fail instead of quietly packaging them.
    return False


def stage_copy(source, folder, name):
    destination = OUT / folder / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    return destination


def apex_version(package):
    """ApexManifest.version (protobuf field 2) from apex_manifest.pb.

    The image's expanded APEXes ship apex_manifest.pb, not apex_manifest.json, so
    the versionCode in the generated apex-info-list.xml has to come from the
    protobuf. Returns 1 when the field is absent, which is what the AOSP parser
    treats as "unspecified".
    """
    path = package / "apex_manifest.pb"
    if not path.is_file():
        return 1
    data = path.read_bytes()

    def varint(position):
        shift = 0
        value = 0
        while True:
            byte = data[position]
            position += 1
            value |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                return value, position

    index = 0
    try:
        while index < len(data):
            key, index = varint(index)
            field, wire = key >> 3, key & 7
            if wire == 0:
                value, index = varint(index)
                if field == 2:
                    return value
            elif wire == 2:
                length, index = varint(index)
                index += length
            elif wire == 5:
                index += 4
            elif wire == 1:
                index += 8
            else:
                break
    except IndexError:
        pass
    return 1


def jar_paths(proto):
    """Extract /apex/... and /system/... .jar strings from a classpath protobuf."""
    # The protobuf string fields are prefixed by a length byte which can be a
    # printable character (e.g. ')' for 41 bytes), so splitting on lines and
    # requiring startswith("/apex/") misses every entry. Match the path itself.
    # The platform half of the class path is /system/framework/*.jar, while the
    # ART half is /apex/*/javalib/*.jar, so both prefixes have to be matched.
    text = "".join(chr(byte) if 32 <= byte < 127 else "\n" for byte in proto)
    return re.findall(r"/(?:apex|system)/[^\x00-\x1f]*?\.jar", text)


def boot_image_classpath(tree):
    """The boot class path string recorded inside the image's boot.oat header.

    The oat header stores the class path dex2oat was given as one colon-joined
    string. ART validates the boot image against the class path it is started
    with, so that string is the only in-image statement of the order the image
    was compiled with (the classpath protobufs do not preserve the exported
    order: the platform one lists core-icu4j first, the image records it last).
    """
    oat = tree / "system" / "framework" / "arm64" / "boot.oat"
    if not oat.is_file():
        return []
    blob = oat.read_bytes()
    start = blob.find(b"/apex/com.android.art/javalib/core-oj.jar:")
    if start < 0:
        return []
    end = start
    while end < len(blob) and 32 <= blob[end] < 127:
        end += 1
    entries = blob[start:end].decode("ascii", "replace").split(":")
    return list(dict.fromkeys(
        entry for entry in entries
        if re.fullmatch(r"/(?:apex|system)/[A-Za-z0-9_.@+-]+(/[A-Za-z0-9_.@+-]+)*\.jar", entry)))


def main():
    skip_dex = "--skip-dex" in sys.argv
    index = json.loads(INDEX.read_text(encoding="utf-8"))["entries"]

    for logical, guest in A15_PROGRAMS.items():
        if guest not in index or index[guest]["type"] != "file":
            raise SystemExit("Missing Android 15 program %s at /%s" % (logical, guest))
    if not (APEX_TREE / "com.android.art" / ".expanded").is_file():
        raise SystemExit("ART APEX is not expanded; run scripts/extract-apex.py first")

    # 64-bit-only assertion: the image must not contain 32-bit ELF files.
    thirty_two = []
    for path, record in index.items():
        if record["type"] != "file" or not path.endswith(".so"):
            continue
        if path.startswith(("system/lib/", "vendor/lib/", "odm/lib/", "product/lib/")):
            thirty_two.append(path)
    if thirty_two:
        raise SystemExit("64-only image contains 32-bit libraries:\n  " +
                         "\n  ".join(thirty_two[:20]))

    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "assets").mkdir(parents=True)
    native = {}
    data = {}
    native_links = []
    used_guest_paths = set()
    library_names = set()
    files = []
    programs_named = set()

    def add_native(source, logical, name, source_kind, library_name=None, guest_path=None,
                   mode=0o755):
        if logical in native:
            return native[logical]
        source = Path(source)
        if not source.is_file():
            raise SystemExit(
                "Missing A15 build output for %s: %s\n"
                "Build the matching phase first; see docs/android15-port/04 and 05." % (logical, source))
        destination = stage_copy(source, "jniLibs/arm64-v8a", name)
        try:
            entry_source = source.relative_to(MODULE).as_posix()
        except ValueError:
            entry_source = source.as_posix()
        entry = {"name": name, "logical": logical, "bytes": destination.stat().st_size,
                 "sha256": digest(destination), "mode": mode, "source": source_kind,
                 "sourcePath": entry_source}
        if library_name:
            entry["libraryName"] = library_name
        if guest_path:
            entry["guestPath"] = guest_path
        files.append(entry)
        native[logical] = entry
        if library_name:
            library_names.add(library_name)
        return entry

    # 1. Source-built API 35 runtime, ROM companion, project bridges.
    for logical, path, name, library, guest_path in RUNTIME_FILES:
        add_native(path, logical, name, "aosp15", library, guest_path)
    native_links.append({"path": "rootfs/" + APEX_RUNTIME + "/bin/linker64",
                         "logical": "linker64"})
    for logical, path, name, library in COMPANION_FILES:
        add_native(path, logical, name, "redroid15", library)
    missing = []
    for logical, path, name, library, guest_path in BRIDGE_FILES:
        if not path.is_file():
            missing.append(logical)
            continue
        add_native(path, logical, name, "project-a15", library, guest_path)
    for logical, path, name in PROGRAM_FILES:
        if not path.is_file():
            missing.append(logical)
            continue
        add_native(path, logical, name, "project-a15")
    # Stage-5 gate 8 modules: the project graphics layer rebuilt for API 35. A
    # missing build is reported and skipped, exactly like the bridges above, so
    # the manifest stays honest instead of pointing at a file that does not exist.
    graphics_missing = []
    # HAL modules that the guest loads through hw_get_module() must exist as
    # regular files inside the instance rootfs, not as symlinks into the APK's
    # nativeLibraryDir. Android 15's libhardware hw_get_module_by_class() calls
    # hw_module_exists(), which realpath()s the candidate and requires the
    # result to start with realpath("/vendor/lib64/hw") + '/' (disassembly:
    # libhardware.so hw_get_module_by_class+0x42c -> 0x4440 -> 0x4550). A
    # symlink resolves to /data/app/.../lib/arm64/liba15vm-guest-*.so, which is
    # outside that directory, so the module is declared missing and composer
    # never even calls dlopen -- exactly the 2026-10-27 20:11 log
    # ("failed to get hwcomposer or gralloc module", no dlopen error).
    # A9's libhardware.so has no realpath reference at all, which is why A9/A10
    # accept the same symlink layout. The host installs these entries as real
    # files (guest-runtime GuestRuntime.prepare()).
    real_files = []
    for logical, name, guest_path in GRAPHICS_FILES:
        path = BRIDGES / "graphics" / "lib" / name
        if not path.is_file():
            graphics_missing.append(logical)
            continue
        # Gradle only packages lib*.so entries from jniLibs, and the guest
        # resolves the DT_NEEDED soname through the libraryName link the host
        # creates, exactly like A9/A10.
        add_native(path, logical, "liba15vm-guest-" + name, "project-a15", name, guest_path)
        if guest_path and guest_path.startswith("/vendor/lib64/hw/"):
            real_files.append({"logical": logical, "guestPath": guest_path})
    # Round 75: same realpath() gate, but for a HAL module that belongs to the
    # *image* instead of the project. The audio HAL service opens the legacy
    # module through hw_get_module("audio.primary"), and the only module in the
    # A15 image is vendor/lib64/hw/audio.primary.default.so (the AOSP stub that
    # accepts and paces PCM without hardware). Staged like every other image .so
    # it would be a symlink into nativeLibraryDir, which A15 libhardware rejects
    # before dlopen, so it is installed as a regular file inside the instance
    # rootfs (realFiles). nativeLinks for the same path is skipped on purpose:
    # the host creates nativeLinks *after* realFiles, so a symlink there would
    # overwrite the real file again.
    image_hal_modules = {
        "audio.primary.default.so": "/vendor/lib64/hw/audio.primary.default.so",
    }
    if graphics_missing:
        print("prepare-a15: graphics modules not built yet, skipped: "
              + ", ".join(sorted(graphics_missing)), file=sys.stderr)
    if missing:
        print("prepare-a15: not built yet, skipped: " + ", ".join(sorted(missing)),
              file=sys.stderr)

    # 2. Android 15 programs the host launches.
    for logical, guest in A15_PROGRAMS.items():
        add_native(TREE / guest, logical, "liba15vm-" + sanitize(logical) + ".so", "redroid15")
        native_links.append({"path": "rootfs/" + guest, "logical": logical})
        used_guest_paths.add(guest)
        programs_named.add(guest)

    # 3. arm64 shared libraries. /system/lib64 first (it is the ROM's default
    # namespace), then APEX payloads, then vendor. Every library keeps its real
    # guest path, so the APEX layout survives.
    candidates = []
    for path, record in index.items():
        if record["type"] != "file" or excluded(path):
            continue
        if not path.endswith(".so"):
            continue
        candidates.append((path, record))
    def rank(item):
        path = item[0]
        if path.startswith("system/lib64/"):
            return (0, path.count("/"), path)
        if path.startswith(APEX_RUNTIME + "/"):
            return (1, path.count("/"), path)
        if path.startswith("apex/"):
            return (2, path.count("/"), path)
        if path.startswith("vendor/lib64/"):
            return (3, path.count("/"), path)
        if path.startswith("system_ext/") or path.startswith("product/"):
            return (4, path.count("/"), path)
        return (5, path.count("/"), path)
    candidates.sort(key=rank)
    for path, record in candidates:
        if path in used_guest_paths:
            continue
        kind = elf_kind(TREE / path)
        if kind is None:
            continue
        if kind == "other":
            raise SystemExit("Unexpected 32-bit ELF in a 64-bit-only image: " + path)
        base = posixpath.basename(path)
        logical = None
        guest_name = "liba15vm-image-" + hashlib.sha256(path.encode()).hexdigest()[:20] + ".so"
        # The runtime's own library names already exist; never shadow them.
        if base not in library_names and base not in native:
            logical = base
        if logical is None:
            logical = "image/" + path
        entry = add_native(TREE / path, logical, guest_name, "redroid15",
                           base if logical == base and base.endswith(".so") else None)
        hal_guest_path = image_hal_modules.get(base)
        if hal_guest_path:
            # Real file inside the instance rootfs (see image_hal_modules above).
            real_files.append({"logical": logical, "guestPath": hal_guest_path})
        else:
            native_links.append({"path": "rootfs/" + path, "logical": logical})
        used_guest_paths.add(path)
        if any(value > 0x4000 for value in elf_align(TREE / path)):
            pass  # 16K alignment is recorded, not rewritten: the bytes stay intact.

    registry = {}

    # 4. Remaining immutable files become assets.
    candidates = []
    for path, record in index.items():
        if record["type"] != "file" or excluded(path):
            continue
        if path in used_guest_paths or (path.endswith(".so") and elf_kind(TREE / path) == "arm64"):
            continue
        candidates.append(path)
    lower_counts = {}
    for path in candidates:
        normal = ASSET_PREFIX + "rootfs/" + path
        key = normal.lower()
        lower_counts[key] = lower_counts.get(key, 0) + 1
    for path in candidates:
        record = index[path]
        normal = ASSET_PREFIX + "rootfs/" + path
        special = normal.endswith((".bc", ".gz"))
        encoded = special or lower_counts[normal.lower()] > 1
        if encoded:
            asset = ASSET_PREFIX + "rom-encoded/" + hashlib.sha256(path.encode()).hexdigest() + ".asset"
        else:
            asset = normal
        destination = stage_copy(TREE / path, "assets", asset)
        mode = stat.S_IMODE(record["mode"])
        # Android 14+ refuses to load a dex file that the calling process can write:
        #   SecurityException: Writable dex file
        #   '/system/system_ext/priv-app/Provision/Provision.apk' is not allowed
        # (real device 2026-10-29 round 82, provision child). On a real device /system is
        # mounted read-only; this rootfs lives in app-private storage, so the write bits
        # have to be dropped here. Recorded per asset, applied by RuntimeFiles.installData.
        if path.endswith((".apk", ".jar", ".dex")):
            mode &= ~0o222
        entry = {"name": asset, "bytes": destination.stat().st_size, "sha256": digest(destination),
                 "source": "redroid15", "mode": mode}
        if encoded:
            entry["installPath"] = "rootfs/" + path
        if asset in data:
            raise SystemExit("Duplicate asset: " + asset)
        data[asset] = entry

    # 5. Symlinks and hard links.
    symlinks = []
    directories = set()
    for path, record in index.items():
        if excluded(path):
            continue
        if record["type"] == "dir":
            directories.add(path)
        elif record["type"] == "symlink":
            target = record["target"]
            resolved = target if target.startswith("/") else posixpath.normpath(
                posixpath.join(posixpath.dirname(path), target))
            if excluded(resolved.lstrip("/")):
                continue
            symlinks.append({"path": "rootfs/" + path,
                             "target": host_symlink_target(path, target)})
    links = []
    for path, record in index.items():
        if record["type"] == "hardlink" and not excluded(path):
            links.append({"path": "rootfs/" + path, "target": "rootfs/" + record["target"]})

    # 6. Class paths from the classpath protobufs.
    #
    # A device exports BOOTCLASSPATH / SYSTEMSERVERCLASSPATH from *two* sets of
    # protobufs: the ART APEX's /apex/com.android.art/etc/classpaths/* and the
    # platform's /system/etc/classpaths/*. Reading only the APEX half (the state
    # until 2026-10-27) leaves ART without a "mainline framework library" and
    # SystemServer without services.jar:
    #
    #   16:24  Check failed: !location.empty()
    #          BOOTCLASSPATH and DEX2OATBOOTCLASSPATH must not be empty
    #   16:32  Check failed: !location.empty() No mainline framework library found
    #   16:39  (same line, instance 1793090395626)
    #
    # ART derives /system/framework/arm64/boot.art from the first non-APEX boot
    # class path entry, i.e. from /system/framework/framework.jar.
    boot_proto = (APEX_TREE / "com.android.art" / "etc" / "classpaths" / "bootclasspath.pb")
    server_proto = (APEX_TREE / "com.android.art" / "etc" / "classpaths" / "systemserverclasspath.pb")
    platform_boot_proto = TREE / "system" / "etc" / "classpaths" / "bootclasspath.pb"
    platform_server_proto = TREE / "system" / "etc" / "classpaths" / "systemserverclasspath.pb"
    if not boot_proto.is_file():
        raise SystemExit("Missing ART boot class path: " + str(boot_proto))
    if not platform_boot_proto.is_file():
        raise SystemExit("Missing platform boot class path: " + str(platform_boot_proto))
    apex_boot = list(dict.fromkeys(jar_paths(boot_proto.read_bytes())))
    platform_boot = list(dict.fromkeys(jar_paths(platform_boot_proto.read_bytes())))
    # Deduplicate while keeping order: the protobuf repeats entries.
    boot = list(dict.fromkeys(apex_boot + platform_boot))
    system_server = list(dict.fromkeys(
        jar_paths(server_proto.read_bytes()) if server_proto.is_file() else []))
    if platform_server_proto.is_file():
        system_server = list(dict.fromkeys(
            system_server + jar_paths(platform_server_proto.read_bytes())))
    # Android 15 assembles SYSTEMSERVERCLASSPATH exactly like BOOTCLASSPATH: every
    # /apex/<name>/etc/classpaths/systemserverclasspath.pb fragment is appended to
    # the two platform entries. Reading only the ART + platform protobufs leaves
    # out all 18 APEX service jars, and SystemServer then dies while constructing
    # PackageManagerService:
    #
    #   java.lang.NoClassDefFoundError: Failed resolution of:
    #       Lcom/android/permission/persistence/RuntimePermissionsPersistence;
    #   at com.android.server.pm.PackageManagerService.<init>
    #
    # That class lives in
    # /apex/com.android.permission/javalib/service-permission.jar (verified by
    # searching the APEX dex files), which is why the classpath has to carry the
    # fragments. Every fragment entry is verified to be staged, like the boot ones.
    for fragment in sorted(APEX_TREE.glob("*/etc/classpaths/systemserverclasspath.pb")):
        for path in jar_paths(fragment.read_bytes()):
            if path in system_server:
                continue
            if not (TREE / path.lstrip("/")).is_file():
                raise SystemExit(
                    "APEX system server class path fragment lists a jar that is not staged: " + path)
            system_server.append(path)
    if not system_server:
        raise SystemExit("systemserverclasspath.pb contained no jar entries")
    if not boot:
        raise SystemExit("bootclasspath.pb contained no jar entries")
    # The protobufs do not preserve the order init finally exports, and ART
    # validates the boot image against the class path it is started with. The
    # image's own boot.oat records that order verbatim, so prefer it and refuse
    # to package a list that the staged image cannot satisfy.
    boot_source = "bootclasspath.pb"
    recorded = boot_image_classpath(TREE)
    if recorded:
        absent = [path for path in recorded if not (TREE / path.lstrip("/")).is_file()]
        if absent:
            raise SystemExit("boot.oat lists jars that are not staged:\n  " +
                             "\n  ".join(absent))
        boot = recorded
        boot_source = "boot.oat"
    # Android 15's ART check needs the whole boot class path, not just the two
    # core jars: libicu_jni.so registers com.android.icu.text.TimeZoneNamesNative
    # from core-icu4j.jar, and okhttp/bouncycastle/apache-xml are resolved lazily
    # by the same protobuf order. Truncating this list makes ART abort in JNIHelp
    # ("unable to find class ...; aborting"). Keep the protobuf order as is.
    art_boot = list(apex_boot)
    # The APEX protobuf does not list the ICU jar: init adds it from the i18n
    # APEX. libicu_jni.so is loaded while ART starts, so it has to be here.
    icu_jar = "/apex/com.android.i18n/javalib/core-icu4j.jar"
    if (APEX_TREE / "com.android.i18n" / "javalib" / "core-icu4j.jar").is_file():
        art_boot.append(icu_jar)

    # Android 15's BOOTCLASSPATH is assembled by init's derive_classpath from
    # *every* /apex/<name>/etc/classpaths/bootclasspath.pb fragment, not only
    # from the ART APEX and the platform one. Those fragments carry the mainline
    # framework jars; the StatsD one carries framework-statsd.jar, which is where
    # android.util.StatsEvent is defined on API 35.
    #
    # Leaving the fragments out is what produced the one B-track failure that had
    # neither a class name nor a stack trace:
    #
    #   java.lang.NoClassDefFoundError: Class not found using the boot class loader;
    #   no stack trace available
    #
    # Real-device evidence (2026-10-28, a15trace breakpoint on the pre-allocated
    # throw sites of libart's art::ClassLinker::FindClass):
    #   site = class_linker.cc:3240 (class_loader == nullptr, boot class path miss)
    #   descriptor = Landroid/util/StatsEvent;   loader_ref = 0x0
    #   caller     = ClassLinker::DoResolveType<ArtMethod*> <- NterpGetMethod
    # i.e. a boot class method interpreted an invoke on android.util.StatsEvent,
    # the type is not in the 16 boot jars, so ART threw its pre-allocated
    # exception, which by construction carries no class name and no frames.
    #
    # boot.oat's recorded list stays the prefix (ART only allows the runtime class
    # path to be a superset: "The boot image did not contain all boot class path
    # components. Load the rest.", runtime.cc); the fragments are appended after
    # it in a stable order. Every appended entry must be staged, otherwise ART
    # aborts while opening the boot class path.
    fragment_jars = []
    for fragment in sorted(APEX_TREE.glob("*/etc/classpaths/bootclasspath.pb")):
        fragment_jars.extend(jar_paths(fragment.read_bytes()))
    appended = []
    for path in fragment_jars:
        if path in boot or path in appended:
            continue
        if not (TREE / path.lstrip("/")).is_file():
            raise SystemExit("APEX boot class path fragment lists a jar that is not staged: " + path)
        appended.append(path)
    boot = boot + appended
    boot_source += "+apex-fragments(%d)" % len(appended)

    # 7. Properties: ROM precedence plus the A15 container overlay.
    properties = {}
    property_files = []
    for source in PROPERTY_SOURCES:
        path = TREE / source.lstrip("/")
        if not path.is_file():
            continue
        property_files.append(source)
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, separator, value = line.partition("=")
            if separator and re.fullmatch(r"[A-Za-z0-9_.-]+", name.strip()):
                properties[name.strip()] = value.strip()
    overlay = A15 / "guest-a15.prop"
    for line in overlay.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, value = line.partition("=")
        properties[name.strip()] = value.strip()
    order = properties.get("ro.product.property_source_order",
                           "product,product_services,odm,vendor,system").split(",")
    for field in ("brand", "device", "manufacturer", "model", "name"):
        name = "ro.product." + field
        if properties.get(name):
            continue
        for partition in order:
            value = properties.get("ro.product." + partition + "." + field)
            if value:
                properties[name] = value
                break
    guest_prop = "".join(name + "=" + value + "\n" for name, value in sorted(properties.items()))
    prop_asset = ASSET_PREFIX + "guest.prop"
    prop_file = OUT / "assets" / prop_asset
    prop_file.parent.mkdir(parents=True, exist_ok=True)
    prop_file.write_text(guest_prop, encoding="utf-8", newline="\n")
    data[prop_asset] = {"name": prop_asset, "bytes": prop_file.stat().st_size,
                        "sha256": digest(prop_file), "mode": 0o644}

    # 8.0 Guest boot animation asset (round 88 / UI-0).
    #
    # /system/bin/bootanimation exists in the image, but redroid ships no
    # /system/media/bootanimation.zip and vendor/build.prop sets
    # debug.sf.nobootanimation=1 (guest-a15.prop overrides it to 0). Without the
    # asset the binary prints "BootAnimation: boot animation disabled" and exits,
    # so scripts/make-bootanimation.py generates a deterministic, stored-only zip
    # that libbootanimation accepts.
    bootanim_source = A15 / "bootanim" / "bootanimation.zip"
    if bootanim_source.is_file():
        bootanim_asset = ASSET_PREFIX + "rootfs/system/media/bootanimation.zip"
        bootanim_file = OUT / "assets" / bootanim_asset
        bootanim_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(bootanim_source, bootanim_file)
        data[bootanim_asset] = {"name": bootanim_asset,
                                "bytes": bootanim_file.stat().st_size,
                                "sha256": digest(bootanim_file), "mode": 0o644}
        directories.add("system/media")
    else:
        print("prepare-a15: bootanimation.zip missing, UI-0 disabled: "
              + str(bootanim_source), file=sys.stderr)

    # 8.0b MIME database (round 89) -- the /system/etc route is DISPROVEN.
    #
    # Screenshots failed with "Unsupported MIME type image/png" from
    # MediaProvider.ensureFileColumns, because the guest's libcore MimeMap is empty.
    # Staging /system/etc/mime.types did not change anything and was removed: A15
    # never reads that path. It builds the map from classpath resources
    # (/res/{debian,android,vendor}.mime.types inside system/framework/framework.jar
    # via android.content.type.DefaultMimeMapFactory), and in this container that
    # lookup returns null even though the jar really contains the entries (probe
    # line: a15_application_mime=fallback(replaced=false, png->null)).
    # The fix lives in vendor/framework/A15MimeTypes.java: it parses those same
    # resources out of the jar and installs them through the framework's own
    # MimeMap.setDefaultSupplier hook, in every guest app process.

    # 8. Host device VINTF entry.
    host_vintf = A15 / "vintf" / "host-devices.xml"
    vintf_asset = ASSET_PREFIX + "rootfs/vendor/etc/vintf/manifest/a10vm-host-devices.xml"
    vintf_file = OUT / "assets" / vintf_asset
    vintf_file.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(host_vintf, vintf_file)
    data[vintf_asset] = {"name": vintf_asset, "bytes": vintf_file.stat().st_size,
                         "sha256": digest(vintf_file), "mode": 0o644}
    directories.add("vendor/etc/vintf/manifest")

    # 8.1 VINTF manifest normalisation (framework + device, every file).
    #
    # The redroid 15 image ships every VINTF XML in compatibility-matrix shape:
    #   <manifest version="9.0" ...>
    # and the framework manifest also carries a <system-sdk> block. A *manifest*
    # is not a compatibility matrix: its schema version is 1.0 and <system-sdk>
    # only exists in matrices. libvintf parses each file and merges them
    # (VintfObject::fetchUnfilteredFrameworkHalManifest -> fetchOneHalManifest
    # + addDirectoryManifests, then HalManifest::addAll); a rejected file loses
    # its HALs, and with every file rejected the merged manifest is empty.
    #
    # That is why the previous rounds of "add one more HAL entry to
    # /system/etc/vintf/manifest.xml" could never change the result: the other
    # 13 files still carried the matrix shape. Normalise every VINTF XML in the
    # staged image instead of hand-writing two of them, so the image's own HAL
    # set (camera, media, stats, netd, sensors, ...) stays intact.
    vintf_roots = ("system/etc/vintf", "system/system_ext/etc/vintf",
                   "system_ext/etc/vintf", "product/etc/vintf",
                   "vendor/etc/vintf", "odm/etc/vintf")
    manifest_root = OUT / "assets" / ASSET_PREFIX / "rootfs"
    manifest_version = re.compile(r'(<manifest\b[^>]*?\bversion=")([^"]*)(")')
    vintf_files = {"framework": [], "device": []}
    for name in vintf_roots:
        directory = manifest_root / name
        if not directory.is_dir():
            continue
        kind = "device" if name.startswith(("vendor/", "odm/")) else "framework"
        for path in sorted(directory.rglob("*.xml")):
            text = path.read_text(encoding="utf-8")
            updated = re.sub(r"\s*<system-sdk>.*?</system-sdk>", "", text, flags=re.DOTALL)
            updated = manifest_version.sub(
                lambda match: match.group(1) + "1.0" + match.group(3), updated, count=1)
            if updated != text:
                path.write_text(updated, encoding="utf-8", newline="\n")
                asset = ASSET_PREFIX + "rootfs/" + path.relative_to(manifest_root).as_posix()
                if asset in data:
                    data[asset]["bytes"] = path.stat().st_size
                    data[asset]["sha256"] = digest(path)
                text = updated
            vintf_files[kind].append(path)
    if not vintf_files["framework"]:
        raise SystemExit("no framework VINTF manifest staged under rootfs/")

    def declares_hidl_manager(paths):
        for path in paths:
            if "android.hidl.manager" in path.read_text(encoding="utf-8"):
                return True
        return False

    manager_hal = (
        "    <hal format=\"hidl\" max-level=\"8\">\n"
        "        <name>android.hidl.manager</name>\n"
        "        <transport>hwbinder</transport>\n"
        "        <fqname>@1.2::IServiceManager/default</fqname>\n"
        "    </hal>\n"
    )

    # hwservicemanager's main() reads IServiceManager::descriptor through GOT
    # slot 0x18fa8 (hwservicemanager .text 0xc100), which resolves to
    #   _ZN7android4hidl7manager4V1_215IServiceManager10descriptorE
    # i.e. "android.hidl.manager@1.2::IServiceManager", then calls
    # getHidlTransport() on the framework manifest first (0x155c4) and on the
    # device manifest second (0x15640). EMPTY from both is the line
    #   "HIDL is not supported on this device so hwservicemanager is not needed"
    # that blocks gate 5.
    #
    # The image already declares that entry in
    # system/system_ext/etc/vintf/manifest.xml (hwbinder, max-level="8"), so
    # after normalisation there is nothing to inject. The two fallbacks below
    # only fire on an image that drops that declaration: one copy in the
    # framework set, one in the device manifest (the second lookup).
    if not declares_hidl_manager(vintf_files["framework"]):
        primary = manifest_root / "system/etc/vintf/manifest.xml"
        if not primary.is_file():
            raise SystemExit("framework manifest missing: " + str(primary))
        text = primary.read_text(encoding="utf-8")
        if "</manifest>" not in text:
            raise SystemExit("framework manifest has no </manifest>: " + str(primary))
        primary.write_text(text.replace("</manifest>", manager_hal + "</manifest>", 1),
                           encoding="utf-8", newline="\n")
        asset = ASSET_PREFIX + "rootfs/system/etc/vintf/manifest.xml"
        if asset in data:
            data[asset]["bytes"] = primary.stat().st_size
            data[asset]["sha256"] = digest(primary)

    if not declares_hidl_manager(vintf_files["device"]):
        device_manifest = manifest_root / "vendor/etc/vintf/manifest.xml"
        if device_manifest.is_file():
            text = device_manifest.read_text(encoding="utf-8")
            if "</manifest>" in text:
                device_manifest.write_text(
                    text.replace("</manifest>", manager_hal + "</manifest>", 1),
                    encoding="utf-8", newline="\n")
                asset = ASSET_PREFIX + "rootfs/vendor/etc/vintf/manifest.xml"
                if asset in data:
                    data[asset]["bytes"] = device_manifest.stat().st_size
                    data[asset]["sha256"] = digest(device_manifest)


    # 8.2 APEX info list, in both mount namespaces.
    #
    # libvintf reads the APEX info list BEFORE it merges any HAL manifest:
    #   VintfObject::fetchUnfilteredFrameworkHalManifest -> HalManifest::fetchAllInformation
    # and a failed read makes GetFrameworkHalManifest/GetDeviceHalManifest both
    # return -13, so hwservicemanager's getHidlTransport() has nothing to look at
    # and prints "HIDL is not supported on this device". That is gate 5 as
    # measured on the real device (2026-10-27 14:48, instance 1793083694069):
    #   Cannot open /bootstrap-apex/apex-info-list.xml: Permission denied
    #   getFrameworkHalManifest: status from fetching VINTF information: -13
    #
    # On a tablet this file is written by apexd at boot; the container runs no
    # apexd, and the image (a Redroid layer) has no apex-info-list.xml at all.
    # Generate the same shape apexd emits, from the APEXes actually present under
    # rootfs/apex/, into both roots:
    #   rootfs/bootstrap-apex/apex-info-list.xml  (bootstrap namespace)
    #   rootfs/apex/apex-info-list.xml            (default namespace)
    # Attribute names come from libvintf's Apex.cpp / apexd's generated writer:
    # moduleName, modulePath, versionCode, versionName, isFactory, isActive,
    # lastUpdateMillis, provideSharedApexLibs. preinstalledModulePath is omitted
    # on purpose: /system/apex/*.apex is excluded from prepared (SKIP_IMAGE_PREFIXES),
    # so pointing at it would reference a file the guest does not have.
    def write_apex_info_list(relative):
        entries = []
        for package in sorted(p for p in APEX_TREE.iterdir() if p.is_dir()):
            name = package.name
            if not (package / "apex_manifest.pb").is_file() and \
                    not (package / "apex_manifest.json").is_file():
                continue
            version = apex_version(package)
            entries.append(
                '    <apex-info moduleName="%s" modulePath="/apex/%s" versionCode="%d"'
                ' versionName="%d" isFactory="true" isActive="true"'
                ' lastUpdateMillis="0" provideSharedApexLibs="false" />'
                % (name, name, version, version))
        if not entries:
            raise SystemExit("no expanded APEX modules under " + str(APEX_TREE))
        text = ('<?xml version="1.0" encoding="UTF-8"?>\n<apex-info-list>\n'
                + "\n".join(entries) + "\n</apex-info-list>\n")
        asset = ASSET_PREFIX + "rootfs/" + relative
        destination = OUT / "assets" / asset
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8", newline="\n")
        data[asset] = {"name": asset, "bytes": destination.stat().st_size,
                       "sha256": digest(destination), "mode": 0o644}
        return len(entries)

    apex_modules = write_apex_info_list("bootstrap-apex/apex-info-list.xml")
    write_apex_info_list("apex/apex-info-list.xml")

    # 9. DEX files.
    #
    # framework-check.dex / runtime-check.dex are prebuilt products committed
    # under artifacts/ (a15-dex/classes.dex, a15-runtime-dex/classes.dex).
    # --skip-dex may skip *recompiling* them, but it must never drop them from
    # the packaged asset list: without these two files the APK ships without
    # them and ART aborts at the self-check with
    #   ClassNotFoundException: io.a10vm.guest.RuntimeCheck
    # That is exactly what a `--skip-dex` prepare caused: dataFiles fell from
    # 2453 to 2451 and the Gradle/Android Studio build silently produced an
    # incomplete APK. Keep the registration outside the flag.
    if not skip_dex:
        build_dex()
    for asset in ("framework-check.dex", "runtime-check.dex"):
        meta_path = A15 / "artifacts" / (asset + ".json")
        meta = (json.loads(meta_path.read_text(encoding="utf-8"))
                if meta_path.is_file() else {"asset": ASSET_PREFIX + asset})
        destination = OUT / "assets" / ASSET_PREFIX / asset
        if not destination.is_file():
            source = A15 / "artifacts" / ("a15-dex" if asset.startswith("framework")
                                          else "a15-runtime-dex") / "classes.dex"
            if not source.is_file():
                raise SystemExit("missing prebuilt dex artifact: " + str(source)
                                 + " (run without --skip-dex once)")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        # Android 14/15 ART enforces read-only Java DCL: DexFile_openDexFileNative
        # calls isReadOnlyJavaDclChecked() and rejects any dex that is still
        # writable by the caller. A9/A10's ART has no such check, so both dex
        # files used to ship as 0644; on A15 that aborts the self-check with
        #   Attempt to load writable dex file: .../runtime-check.dex
        #   SecurityException: Writable dex file '...' is not allowed.
        #   Check failed: system_class_loader != nullptr
        # (real-device log 2026-10-27, instance 1793088882545). 0444 is also what
        # a real device ships for system dex, and ART only needs read access.
        data[meta["asset"]] = {"name": meta["asset"], "bytes": destination.stat().st_size,
                               "sha256": digest(destination), "mode": 0o444}

    # 10. System packages.
    packages = {}
    for directory in ("system/app", "system/priv-app", "system/product/app",
                      "system/product/priv-app", "system/system_ext/app",
                      "system/system_ext/priv-app", "vendor/app", "vendor/priv-app"):
        if not (TREE / directory).is_dir():
            continue
        for root, _, names in os.walk(TREE / directory):
            for name in names:
                if not name.endswith(".apk"):
                    continue
                absolute = Path(root) / name
                relative = absolute.relative_to(TREE).as_posix()
                packages[relative] = "/" + relative
    named = {
        "framework": "system/framework/framework-res.apk",
        "launcher": "system/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk",
        "systemui": "system/priv-app/SystemUI/SystemUI.apk",
        "settings": "system/priv-app/Settings/Settings.apk",
        "settingsProvider": "system/priv-app/SettingsProvider/SettingsProvider.apk",
        "packageInstaller": "system/priv-app/PackageInstaller/PackageInstaller.apk",
        "shell": "system/priv-app/Shell/Shell.apk",
        "mediaProvider": "system/priv-app/MediaProvider/MediaProvider.apk",
        "webview": "system/app/webview/webview.apk",
    }
    for key, relative in named.items():
        if relative in packages:
            packages[key] = "/" + relative

    # 11. Manifest.
    initial = set(directories)
    initial.update({"system/bin", "system/lib64", "vendor/bin/hw", "vendor/lib64/hw",
                    "apex", "bootstrap-apex",
                    APEX_RUNTIME + "/bin", APEX_RUNTIME + "/lib64/bionic",
                    APEX_ART + "/bin", APEX_ART + "/lib64", "cache", "acct", "config",
                    "metadata", "lost+found", "storage/emulated/0", "data/local/tmp",
                    "data/dalvik-cache", "data/dalvik-cache/arm64", "data/anr",
                    "data/tombstones", "data/misc/user", "data/misc/profiles/cur",
                    "data/misc/profiles/ref", "data/misc/shared_relro", "data/misc/media",
                    "data/misc/mediadrm", "data/mediadrm", "data/vendor/mediadrm",
                    "data/misc/keystore", "data/misc/keymaster", "data/system",
                    # installd writes /data/misc/installd/layout_version while firing up;
                    # without the directory it aborts with
                    #   Failed to save version to /data/misc/installd/layout_version:
                    #   No such file or directory
                    #   installd: Could not create directories; exiting.
                    # (real-device 2026-10-27 19:42, vm/framework-installer.log)
                    "data/misc/installd", "data/misc/user/0",
                    # statsd's own paths, extracted from the binary's rodata: it
                    # opens/stats these while initialising and dies when the chain is
                    # missing (the same failure shape installd had in D1).
                    "data/misc/stats-active-metric", "data/misc/stats-data",
                    "data/misc/stats-data/restricted-data", "data/misc/stats-metadata",
                    "data/misc/stats-service", "data/misc/train-info",
                    "data/system/users", "data/system/users/0", "data/user", "data/user/0",
                    "data/user_de", "data/user_de/0", "data/app", "data/app-lib",
                    "data/misc/profiles/cur/0", "dev/input",
                    # apexd (the "apexservice" provider) stats these while firing
                    # up and aborts with SIGABRT when they are missing. Names are
                    # the binary's own strings, not guesses.
                    "metadata/apex", "metadata/apex/sessions",
                    "data/apex", "data/apex/active", "data/apex/backup",
                    "data/apex/decompressed", "data/apex/ota_reserved",
                    "data/apex/sessions"})
    profile = {
        "graphics": True,
        "systemSuspend": False,
        "idmap2d": False,
        "health": False,
        # Gate 7 (keystore2) stays off until the A15 keymaster path exists: the
        # A9 recipe launches keymaster@3.0 first and waits for an HIDL marker
        # that keystore2 never prints. Turning it on now would only abort the
        # desktop chain at a gate the image cannot satisfy yet. This is a
        # documented limitation, not a completed gate (see STATUS.md).
        "keystore": False,
        "audio": False,
        "media": False,
        "installer": True,
        "dexoptShim": False,
        "camera": False,
        # The project graphics layer (EGL/GLES/gralloc/hwcomposer + HAL module
        # indirection) only exists once bridges/build-graphics-a15.sh has run;
        # keep the role declared but disabled until the artifacts are packaged.
        "halModules": (BRIDGES / "graphics" / "lib" / "liba15vm-hal-modules.so").is_file(),
        # No configstore service binary exists in the A15 image (only the
        # configstore@1.1 client library that SurfaceFlinger links). Redroid 15
        # itself boots SF without it, i.e. SF falls back to defaults; launching a
        # role whose program is not in the image would only abort the chain with
        # "Missing runtime input: graphics-configstore".
        "configstore": False,
        "registryHelpers": False,
        "dexCache": False,
        # Android 15 DOES have a HIDL service manager (system_ext, see
        # A15_PROGRAMS) and HIDL HALs still register over /dev/hwbinder: the A15
        # composer@2.1 allocator@2.0 and SurfaceFlinger all open it. So the host
        # starts the hwbinder broker + hwservicemanager like A9.
        "hwbinder": True,
        # ...but A15 merged the hwbinder transport into libhidlbase and no longer
        # ships libhwbinder.so, so that entry must stay out of LD_PRELOAD.
        "hwbinderPreload": False,
        "appClass": "io.a10vm.guest.A15GuestApplication",
        "keymasterLogical": "keymaster",
        "keystoreLogical": "keystore2",
        "extraPreloads": [],
        "bootEntry": "io.a10vm.guest.A15Boot",
        # F track: the profile used to name io.a10vm.guest.A15DesktopCheck, a class that
        # never existed in any build input, so `framework-client.log` stayed 0 bytes and the
        # session always died with "Missing desktop process identity". A15 reuses the shared
        # DesktopCheck (its only A10-specific line, the API 29 assertion, now accepts 35).
        "checkClass": "io.a10vm.guest.DesktopCheck",
        "inputClass": "io.a10vm.guest.DesktopInput",
        # B-track instrument (temporary history):
        #   27th round: `trace_end` probe  -> every statement of the window runs fine alone.
        #   28th round: `-Xint -Xmethod-trace` -> ABORTS: ART needs
        #               /data/misc/trace/method-trace-file.bin, the guest has no such dir.
        #   29th round: `-Xint` alone -> failure line BYTE-IDENTICAL (interpreter/compiled
        #               code is not the variable).
        #   30th round: image no longer ships the class-path oat/vdex triples
        #               (SKIP_IMAGE_PREFIXES) -> failure line BYTE-IDENTICAL, but the log
        #               now shows `SystemServer ... 0 (NotReady)`, i.e. the class is
        #               verified at RUNTIME where it used to be preverified.
        # That new fact is what round 31 tests: with the prebuilt verification data gone,
        # `-Xverify:none` removes the runtime verifier from the picture as well. If the
        # failure changes, verification-time type resolution of a services.jar method is
        # the actor; if it is byte-identical, the dex cache/class-loader binding is.
        "serverArtArguments": ["-Xverify:none"],
        "artStartupSeconds": 240,
        # B track: `apexservice` is NOT started from here.
        #
        # Round 43 tried the obvious thing -- launch the image's real apexd
        # (912 KB) as a framework service, uid/gid 0, before composer. Result
        # (instance 1793176749366): apexd does start and even prints
        #   apexd: Started. subcommand = (null)
        #   ... Scanning /metadata/apex/sessions ... Selecting APEX for activation
        #   Failed to activate /vendor/apex/com.android.hardware.cas.apex:
        #     Could not create loop device for ...
        #   Activated 0 packages.  OnStart done, duration=3015
        #   AidlLazyServiceRegistrar: Registering service apexservice
        # but the servicemanager never sees the name: vm/framework-manager.log
        # keeps answering the server with
        #   "Since 'apexservice' could not be found trying to start it as a lazy
        #    AIDL service"  /  "... was unable to"
        # and no uid-0 addService ever reaches its permission shim.
        #
        # Making real apexd work here would mean solving mount/verify/SELinux
        # inside a container that does not need any of it -- every gate it
        # passes only aborts on the next one. The runtime needs exactly one
        # thing: a binder service named "apexservice" that answers the 1-2
        # queries SystemServer actually makes (PlatformCompat ->
        # ApexManager.getActiveApexInfos(), to be confirmed with a SIGQUIT Java
        # stack dump). See
        # docs/android15-port/12 搂5.7 and
        # _audit/coding-log/2026-10-28-CL-A15-apexservice-鍗犱綅鏂规涓巃pexd璇曢敊.md.
        # Android 15's PowerManagerService needs the AIDL HAL service
        # "android.hardware.power.IPower/default" on /dev/binder. Round 45's
        # device log (instance 1793179533405) shows SystemServer reaching
        # StartPowerManager and then printing that exact wait line 9 times.
        # The image ships the default implementation as a normal vendor service
        # binary, so the host launches it like composer/allocator: same broker,
        # same uid 0, only the binder-devices preload so /dev/binder is the
        # project endpoint.
        "services": [
            {"role": "power", "logical": "power", "uid": "0", "gid": "0",
             "preload": "liba15vm-binder-devices.so"},
            # init's rc for this one is `user system / group system wakelock /
            # capabilities BLOCK_SUSPEND`. run-as keeps uid 1000/gid 1000 here;
            # the capability is not obtainable without init and is not needed for
            # the service to answer ISystemSuspend.
            {"role": "system-suspend", "logical": "system-suspend", "uid": "1000", "gid": "1000",
             "preload": "liba15vm-binder-devices.so"},
            # Round 64: StartBatteryService is the first startCoreServices entry and the
            # guest dies with exit 139 immediately after it (no Java exception, no crash
            # handler line => native fault in BatteryMonitor, which binds
            # android.hardware.health.IHealth/default and dereferences it).
            # init's rc for the AIDL default impl is `user system / group system`.
            {"role": "health", "logical": "health", "uid": "1000", "gid": "1000",
             "preload": "liba15vm-binder-devices.so"},
            # Round 71: init.rc is `user statsd / group statsd log`; AID_STATSD is
            # 1066. Only one gid can be carried by A10VM_GUEST_GID, and statsd does
            # not need the `log` group to answer the "stats" AIDL name.
            {"role": "stats", "logical": "stats", "uid": "1066", "gid": "1066",
             "preload": "liba15vm-binder-devices.so"},
            # init.rc for this ROM binary is "service sensorservice
            # /system/bin/sensorservice" with user/group system. The launch order in
            # this list is what the host uses, and it must happen before the server
            # reaches DeviceStateManagerService.
            {"role": "sensorservice", "logical": "sensorservice", "uid": "1000", "gid": "1000",
             "preload": "liba15vm-binder-devices.so"},
            {"role": "artd", "logical": "artd", "uid": "0", "gid": "0",
             "preload": "liba15vm-binder-devices.so"},
            # Round 75: AudioService (SystemServer.startOtherServices -> line 2359)
            # blocks its main thread in the constructor's first JNI call and the
            # process is killed by the watchdog 65 s later. The guest has no
            # audioserver and no audio HAL because this runtime has no init.
            # Both are ROM binaries, launched in init.rc order (the HAL registers
            # first, audioserver binds to it):
            #   vendor.audio-hal  = vendor/bin/hw/android.hardware.audio.service
            #                       (HIDL @7.0::IDevicesFactory/default, hwbinder)
            #   audioserver       = system/bin/audioserver
            # They must be up before the server process reaches StartAudioService,
            # which is why they are profile services (launched before the server)
            # rather than something the framework could start on demand.
            # uid/gid from the image's init rc: user audioserver (1041),
            # group audio (1005). Host PCM is NOT wired here: the HAL loads the
            # image's own stub module audio.primary.default.so, so audio output is
            # silent until a later track replaces it (documented limitation).
            {"role": "audio-hal", "logical": "audio-hal", "uid": "1041", "gid": "1005",
             "preload": "liba15vm-binder-devices.so"},
            # Round 89: the media stack. mediaserver publishes media.player /
            # media.codec and media.resource_manager (the Codec2 resource manager).
            # Without it the codec2 client inside MediaProvider blocks in
            #   ServiceManagerCppClient: Waited one second for media.resource_manager
            # and MediaProvider misses the 10 s provider-publish window:
            #   ActivityManager: Killing ...:com.android.providers.media.module
            #     (adj 0): timeout publishing content providers
            #   ActivityManager: Killing ...:com.android.gallery3d (adj 0):
            #     depends on provider com.android.providers.media.module/...
            # (instance 1793358671561, 19:13-19:18) -- the "Gallery hangs / Files
            # app fails to load" symptom. The codec2 software store itself is
            # mediaswcodec (see A15_PROGRAMS), and it asks mediaserver for that
            # resource manager, so mediaserver must come first. init.rc for both:
            # user media / group audio -> AID_MEDIA 1013, AID_AUDIO 1005.
            {"role": "media", "logical": "media", "uid": "1013", "gid": "1005",
             "preload": "liba15vm-binder-devices.so"},
            # The codec2 software store itself (mediaswcodec). It asks mediaserver
            # for media.resource_manager, hence the order above.
            {"role": "media-codec", "logical": "media-codec", "uid": "1013", "gid": "1005",
             "preload": "liba15vm-binder-devices.so"},
            {"role": "audioserver", "logical": "audioserver", "uid": "1041", "gid": "1005",
             "preload": "liba15vm-binder-devices.so"},
            # Round 78: the two halves of gate 7. keymaster registers the HIDL 4.1
            # IKeymasterDevice/default on /dev/hwbinder first; keystore2 then
            # publishes android.system.keystore2.IKeystoreService/default on
            # /dev/binder (LockSettingsService.initializeSyntheticPassword needs it).
            # Success is read from each service's own log, not from the server:
            #   framework-keymaster.log "Registration complete for
            #     android.hardware.keymaster@4.1::IKeymasterDevice/default."
            #   framework-keystore2.log "Successfully registered Keystore 2.0 service."
            {"role": "keymaster", "logical": "keymaster", "uid": "1000", "gid": "1000",
             "preload": "liba15vm-binder-devices.so"},
            {"role": "keystore2", "logical": "keystore2", "uid": "1017", "gid": "1017",
             "preload": "liba15vm-binder-devices.so",
             "arguments": ["/data/misc/keystore"]},
            # No "netd" process: round 73 removed the 72g entry. The binary dies in
            # libnetd_updatable_init (BPF is EPERM here), so it can never answer the
            # AIDL name "netd". The endpoint comes from the bootstrap dex instead
            # (A15Boot -> GuestNetworks.registerNetworkService), which is what
            # API 28 already does. See A15_PROGRAMS comment above.
        ],
    }
    manifest = {
        "guestSdk": 35,
        "frameworkSdk": 35,
        "loaderFromSource": True,
        "rootRequired": False,
        "artIntegrated": True,
        "frameworkIntegrated": True,
        "desktopImplemented": True,
        "apexAliases": False,
        "apexLayout": True,
        "assetPrefix": ASSET_PREFIX,
        "runtimeRoot": "/apex/com.android.art",
        "tzdataRoot": "/apex/com.android.tzdata",
        "vintfAllManifests": False,
        "hostDevicesManifest": "vendor/etc/vintf/manifest/a10vm-host-devices.xml",
        # A15 has no VNDK directories; the host must not add vndk-* search paths.
        "vndkSearch": False,
        "pageSize": 16384,
        "bootClassPath": boot,
        "artBootClassPath": art_boot,
        "systemServerClassPath": system_server,
        "platformLibraries": PLATFORM_LIBRARIES,
        "registryLibraries": registry,
        "desktopPackages": packages,
        "allSystemPackages": True,
        "systemApkCount": len(packages),
        "propertyFiles": property_files,
        "propertyCount": len(properties),
        "extraPreloads": [],
        "runtimeProfile": profile,
        "symlinks": symlinks,
        "links": links,
        "nativeLinks": native_links,
        "systemImagePaths": len(index),
        "mutableDataPreserved": True,
        "initialDirectories": sorted(initial),
        "sourceImageSha256": json.loads((A15 / "downloads" / "manifest.json").read_text(
            encoding="utf-8"))["layers"][0]["sha256"],
        "files": files,
        "realFiles": real_files,
        "data": list(data.values()),
    }
    # Isolation gate: provenance + content hash.
    foreign_hashes = set()
    for sibling in ("guest-runtime-a9", "guest-runtime"):
        manifest_path = (MODULE.parent / sibling / "prepared" / "assets" /
                         ("guest-runtime-a9-inputs.json" if sibling.endswith("a9")
                          else "guest-runtime-inputs.json"))
        if not manifest_path.is_file():
            continue
        previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        for entry in previous.get("files", []):
            foreign_hashes.add(entry["sha256"])
    violations = []
    for entry in files:
        logical = entry["logical"]
        if entry.get("source") not in {"redroid15", "aosp15", "project-a15"}:
            violations.append("unknown source %r: %s" % (entry.get("source"), logical))
        if entry.get("sha256") in foreign_hashes:
            violations.append("A9/A10 binary content: %s (%s)" % (logical, entry["sourcePath"]))
        if logical.startswith(("liba9vm-", "liba9", "liba10vm-", "liba10")):
            violations.append("foreign logical name: %s" % logical)
    if violations:
        raise SystemExit(
            "A15 isolation gate failed:\n  " + "\n  ".join(sorted(set(violations)))
            + "\nBuild the API 35 runtime and bridges first; see docs/android15-port/04 and 05.")

    sources = {}
    for entry in files:
        bucket = sources.setdefault(entry["source"], {"count": 0, "files": {}})
        bucket["count"] += 1
        bucket["files"][entry["logical"]] = entry["sha256"]
    (OUT / "sources.json").write_text(
        json.dumps(sources, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")

    manifest_file = OUT / "assets" / "guest-runtime-a15-inputs.json"
    manifest_file.write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"nativeFiles": len(files), "dataFiles": len(data),
                      "apexInfos": apex_modules,
                      "symlinks": len(symlinks), "packages": len(packages),
                      "bootClassPath": len(boot), "bootClassPathSource": boot_source,
                      "systemServerClassPath": len(system_server),
                      "prepared": str(OUT),
                      "sources": {key: value["count"] for key, value in sources.items()}},
                     indent=2))


def build_dex():
    classes = A15 / "artifacts" / "a15-classes"
    runtime_classes = A15 / "artifacts" / "a15-runtime-classes"
    stub_classes = A15 / "artifacts" / "a15-stubs"
    for directory in (classes, runtime_classes, stub_classes):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    # Compile-time stubs (bootstrap/stubs/**): signatures only, for the @hide and
    # D8-generated types that io.a10vm.guest.A15Statements must call with a
    # *direct* call site instead of reflection. They are on javac's -classpath
    # only; d8 never sees them, so framework-check.dex keeps referencing the real
    # classes from framework.jar/services.jar.
    stub_sources = sorted((A15 / "bootstrap" / "stubs").rglob("*.java"))
    if stub_sources:
        compile_java(stub_sources, stub_classes)
    framework_sources = [
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15Boot.java",
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15Statements.java",
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15ApexService.java",
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15GateKeeper.java",
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15GuestApplication.java",
        # W-track (round 90): the child-zygote endpoint A15AppLaunch starts for a
        # webview_zygote/app_zygote request. Without an entry here javac fails with
        # "cannot find symbol: A15GuestChildZygote" -- the same class of mistake the
        # restored script made with A15Zygote/A15AppLaunch in round 89.
        A15 / "bootstrap" / "io" / "a10vm" / "guest" / "A15GuestChildZygote.java",
        # Round 79/84: the guest app-process tree. Both were added after the file
        # list was written and the list was never extended, which is why the
        # restored script failed javac with "cannot find symbol: A15Zygote"
        # (A15Boot.start() calls it). The authoritative inventory is the class list
        # of the last good framework-check.dex (docs/android15-port/18).
        A15 / "vendor" / "framework" / "A15Zygote.java",
        A15 / "vendor" / "framework" / "A15MimeTypes.java",
        A15 / "vendor" / "framework" / "A15AppLaunch.java",
    ]
    # Shared framework classes, same list as A9 until each one is verified on
    # API 35. Compiling them together keeps the CLASSPATH complete for the
    # desktop stage; behaviour changes belong in the per-class API 35 branches.
    for name in ("DesktopCheck", "DesktopInput", "DesktopProcesses", "GuestMemory", "GuestScheduling",
                 "GuestChildZygote", "GuestDisplayState", "PrivateVolumes", "LocalGuestService",
                 "GuestCapabilities", "GuestSetup", "GuestInputMethods",
                 "GuestHardwareApplications", "GuestWebViewProviders", "DesktopHome",
                 "GuestCommand", "GuestInstall", "GuestMediaScan", "AppLaunch",
                 "AppRuntimeOptions", "GuestWebView", "AppDexCache", "GuestNetworks",
                 "GuestConnectivity", "GuestWifi", "GuestWifiChannel", "GuestDiscovery",
                 "GuestDnsResolver", "GuestOemNetd", "A15BpfMaps", "ProcessEvidence"):
        framework_sources.append(A15 / "vendor" / "framework" / (name + ".java"))
    missing = [str(path) for path in framework_sources if not path.is_file()]
    if missing:
        raise SystemExit("missing framework sources:\n  " + "\n  ".join(missing))
    compile_java(framework_sources, classes, stub_classes)
    d8(classes, A15 / "artifacts" / "a15-dex")
    runtime_sources = [A15 / "vendor" / "framework" / name for name in
                       ("RuntimeCheck.java", "DirectoryCheck.java", "ProcessEvidence.java")]
    compile_java(runtime_sources, runtime_classes)
    d8(runtime_classes, A15 / "artifacts" / "a15-runtime-dex")
    for source, asset in ((A15 / "artifacts" / "a15-dex" / "classes.dex", "framework-check.dex"),
                          (A15 / "artifacts" / "a15-runtime-dex" / "classes.dex", "runtime-check.dex")):
        destination = OUT / "assets" / ASSET_PREFIX / asset
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        (A15 / "artifacts" / (asset + ".json")).write_text(json.dumps({
            "asset": ASSET_PREFIX + asset, "source": str(source),
            "bytes": destination.stat().st_size, "sha256": digest(destination)}), encoding="utf-8")


def run_tool(command, label):
    log = A15 / "artifacts" / (label + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, "w", encoding="utf-8", errors="replace") as stream:
        code = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT).returncode
    if code:
        raise SystemExit(label + " failed (exit " + str(code) + "):\n" +
                         log.read_text(encoding="utf-8", errors="replace"))


def compile_java(sources, destination, classpath=None):
    destination.mkdir(parents=True, exist_ok=True)
    entries = [str(ANDROID_JAR)]
    if classpath is not None:
        entries.append(str(classpath))
    command = (["javac", "--release", "11", "-cp", os.pathsep.join(entries),
                "-d", str(destination)] + [str(x) for x in sources])
    run_tool(command, "javac")


def d8_command(destination, class_files, windows=None):
    """argv for the SDK's d8.

    Windows ships d8.bat, which can only be started through cmd.exe; the POSIX
    build-tools ship a plain d8 executable. Hard-coding "cmd" made CI fail with
    FileNotFoundError: [Errno 2] No such file or directory: 'cmd'
    (build-tools 35.0.0, ubuntu runner, 2026-10-30). The platform switch is a
    parameter so the POSIX branch can be asserted on a Windows host.
    """
    if windows is None:
        windows = os.name == "nt"
    prefix = ["cmd", "/c"] if windows else []
    return prefix + [str(d8_binary(windows)), "--min-api", "35",
                     "--lib", str(ANDROID_JAR),
                     "--output", str(destination)] + class_files


def d8(classes, destination):
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    class_files = [str(x) for x in classes.rglob("*.class")]
    run_tool(d8_command(destination, class_files), "d8")


if __name__ == "__main__":
    main()
