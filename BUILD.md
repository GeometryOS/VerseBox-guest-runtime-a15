# A15 运行时构建说明（API 35）

本文说明 `runtime/` 下每个产物是什么、从哪来、按什么顺序构建、失败时先看哪里。
与 `STATUS.md` 的分阶段验收、`README.md` 的构建入口配套使用。

> **当前状态：本文是构建契约，不是“已验证”的报告。**
> 镜像取证、源码落盘、编译结果都以 `STATUS.md` 为准；这里写的是“必须满足什么”，
> 不是“已经满足”。

---

## 1. 为什么要有这一套

A15 的运行方式与 A9/A10 相同：宿主 App 扮演 init，项目源码构建的 linker/Bionic
把客体绝对路径映射到 App 私有目录：

- `A10VM_ROOTFS`：客体根文件系统；
- `A10VM_HOST_ROOT`：APK 原生库目录；
- `A10VM_STATE_ROOT`：属性/状态目录；
- `A10VM_LD_CONFIG`：私有 linker 配置；
- `A10VM_PROPERTIES`：私有属性区。

这套环境变量名沿用 A10/A9（宿主侧共用），**不是** A15 特有设计。
A15 不能直接用 A9/A10 的二进制：它们的 ABI、ART 版本、APEX 布局都不同。
正确路线是：

```text
AOSP android-15.0.0_r* + 项目文件系统/属性补丁
        ↓
source-linker64 / libc.so / libdl.so / libm.so（全部 API 35）
        ↓
Redroid 15 的 ART / framework / HAL / 系统 APK（原样保留，按 APEX 布局）
```

---

## 2. 固定输入

| 仓库 | 参考 | 用途 |
| --- | --- | --- |
| bionic | `refs/tags/android-15.0.0_r*`（`A15_TAG` 可覆盖） | linker、libc、libdl、libm |
| system/core | 同上 | libbase、libutils、libcutils、libziparchive |
| system/logging | 同上 | liblog（A15 已移出 system/core） |
| system/core 内的 property_service/libpropertyinfoparser | 同上 | 属性解析器（A15 就在 system/core 里） |
| external/llvm-project（NDK 29 的 libc++ 21 头 + 静态库） | NDK 29.0.14206865 | A15 的 linker/libc 需要 C++20；`external/libcxx` 是 2018 年的 libc++ 8，不能用 |
| external/libunwind_llvm | **不拉取** | r36 已无该仓库；libc.bp 只引用名字 `libunwind-exported`，由 `EXTERNAL_PRELINKED` 交给 NDK 的 libunwind.a（build-libc.sh 链接） |
| external/{gwp_asan,scudo,llvm-libc,arm-optimized-routines} | 同上 | select-libc-sources.py 必须解析这些 Android.bp，否则报 missing blueprint |
| external/zlib | 同上 | linker 的 zip 支持 |
| external/jemalloc_new | 同上 | libc 的分配器（A15 路径） |

Redroid 15 客体：

```text
docker.io/redroid/redroid:15.0.0_64only-latest
architecture: linux/arm64
index:    sha256:b51bde9cef80f7bd7581148192f2b2f4d41f23c6344cfe88eceeb8ddd67490ee
image:    sha256:dc2024a999dd0acb1112a23cddc9537ec8cb60a0dbe5a87ce9fbd75a2f5e94d2
layer 0:  sha256:698f1d07c642a52dec5d3367772fc438cbefdd253b2442da74d653dc135fefbc
         693,612,895 bytes
```

标签是滚动标签。**一旦锁定到某个日期版本（`15.0.0_64only-YYYYMMDD`），
就把 `REDROID_TAG` 固定下来**，否则重新下载的“latest”可能换掉整个用户空间。

---

## 2.1 图形源与 desktop-gpu-host 的入库位置

`prepare-a15.py` 要求 `bridges/bin/desktop-gpu-host`（宿主 GPU 端点），
`build-graphics-a15.sh` 要求 goldfish/qemu 源与补丁。它们现在都在仓库内：

```text
guest-runtime-a15/
  third_party/graphics/
    container/                      container-probe/graphics 的副本：
                                    platform.patch、desktop/*.patch、
                                    sources.txt / sources.sha256、
                                    build-desktop.sh / build-desktop-host.sh / fetch-sources.sh
    a9/generated/                   emugen 输出（gl2_enc/gl2_dec …）
    a9/definitions/                 renderControl 定义
    a9/framework/                   HAL / nativewindow 头
    a9/patches/gralloc-api28.patch  gralloc 的 API 28 修正
    a9/upstream/                    由 scripts/fetch-graphics-sources.sh 拉取（不入库）
    codec-id.txt                    与 pinned desktop-gpu-host 匹配的 codec id
    compat/android/base/Log.h       qemu 子集缺的 Log.h 垫片（只提供 CHECK 宏）
  prebuilt/arm64/desktop-gpu-host   宿主 GPU 端点（6,471,456 B / 6578B9D8…，pinned）
  prebuilt/arm64/desktop-gpu-host.sha256
  scripts/fetch-graphics-sources.sh 按 sources.txt 拉取 pinned goldfish/qemu 源并校验 sources.sha256
  scripts/install-desktop-host.sh   校验 sha256 后把 prebuilt 复制到 bridges/bin/
  scripts/build-desktop-host.sh     从仓库内源码重编 host（不需要 A10 流水线）
```

**为什么默认用 pinned**：desktop-gpu-host 是宿主侧端点（运行在 app 进程、
与客体 API 级别无关，A9/A10/A15 共用），pinned 版本已经过真机验证，默认不重编。

**没有 A10 流水线也能重编**（2026-10-30 实测，输出 6,460,960 B）：

```sh
sh scripts/fetch-graphics-sources.sh   # 只在 third_party/graphics/a9/upstream 缺失时
sh scripts/build-desktop-host.sh       # 输出 bridges/bin/desktop-gpu-host
```

它只依赖本仓库：`third_party/graphics/container/` 的 desktop/native 源码、
`a9/generated` 的 emugen 输出、`a9/upstream` 的 goldfish/qemu 源码
（含 `android/base/files/Stream.cpp`）、`third_party/graphics/compat/android/base/Log.h`
（qemu 子集缺这个头，只用 CHECK 宏）和 NDK 29。`A15_REBUILD_DESKTOP=1 sh scripts/build.sh`
即走这条路径；不设置时仍安装 pinned 版本。

**构建顺序里新增的两步**：

```sh
sh scripts/install-desktop-host.sh     # 幂等；校验 sha256，复制到 bridges/bin/
sh scripts/fetch-graphics-sources.sh   # 只在 third_party/graphics/a9/upstream 缺失时需要
```

`build.sh` 默认调用 `install-desktop-host.sh`；`A15_REBUILD_DESKTOP=1` 时改为调用
`build-desktop-host.sh`（缺 upstream 会先拉）。`build-a15.ps1` 默认同样装 pinned，
只在要重编图形层时才拉源。

---

## 3. 构建顺序（有依赖，不能并行乱序）

```text
fetch-a15-sources.sh
        ↓
build-static.sh        生成 liba15vm-bootstrap.a
        ↓
build-linker.sh        生成 bin/source-linker64（含直接执行模式）
        ↓
build-libc.sh          生成 lib/libdl.so、lib/libc.so
        ↓
build-libm.sh          生成 lib/libm.so
```

完整顺序（**一键入口**：Windows `guest-runtime-a15\scripts\build-a15.cmd`（包装 `build-a15.ps1`），
Linux/Git Bash `sh guest-runtime-a15/scripts/build.sh`；已有产物的阶段会跳过，
`A15_FORCE=1` 强制重编原生阶段）：

| 阶段 | 命令 | 产物 |
| --- | --- | --- |
| 镜像 | `python scripts/fetch-redroid15.py` | `downloads/*.tar.gz`、`downloads/manifest.json` |
| 解包 | `python scripts/extract-rootfs.py` | `rootfs/`、`rootfs-index.json` |
| APEX | `python scripts/extract-apex.py` | `rootfs/apex/*` + `com.android.art/.expanded` |
| 运行时源码 | `sh runtime/fetch-a15-sources.sh` | `runtime/upstream/` |
| 静态 Bionic | `sh runtime/build-static.sh` | `runtime/build/lib/liba15vm-bootstrap.a` |
| linker | `sh runtime/build-linker.sh` | `runtime/build/bin/source-linker64` |
| libc / libdl | `sh runtime/build-libc.sh` | `runtime/build/lib/libc.so`、`libdl.so` |
| libm | `sh runtime/build-libm.sh` | `runtime/build/lib/libm.so` |
| 属性桥 | `sh bridges/build-property.sh` | `bridges/lib/liba15vm-property-client.so` |
| Binder 桥 | `sh bridges/build-binder.sh` | `bridges/lib/liba15binder.so` |
| 设备桥 | `sh bridges/build-devices.sh` | `bridges/lib/liba15vm-guest-devices.so` |
| guest 日志 | `sh bridges/build-guest-log.sh` | `bridges/lib/liba15vm-guest-log.so` |
| JNI 注册 | `sh bridges/build-jni-register.sh` | `bridges/lib/liba15vm-jni-register.so` |
| 图形源（仅缺 upstream 时） | `sh scripts/fetch-graphics-sources.sh` | `third_party/graphics/a9/upstream/`（按 sources.sha256 校验） |
| 宿主 GPU 端点（默认） | `sh scripts/install-desktop-host.sh` | `bridges/bin/desktop-gpu-host`（pinned，sha256 校验） |
| 宿主 GPU 端点（源码重编） | `sh scripts/build-desktop-host.sh` | 同上；需 NDK 29 + 已拉取的图形源，不需要 A10 流水线 |
| 图形层 | `sh bridges/build-graphics-a15.sh` | `bridges/graphics/lib/*`（EGL/GLES/gralloc/HWC） |
| HAL 程序（必需） | `sh bridges/build-host-hal.sh` | `bridges/bin/host-devices`（vibrator/sensors/thermal）；`liba15vm-hal-modules.so` 目前未产出，prepare 会跳过 |
| 开机动画素材 | `python scripts/make-bootanimation.py` | `bootanim/bootanimation.zip` |
| prepared | `python scripts/prepare-a15.py` | `prepared/`（含 `system/media/bootanimation.zip`） |
| APK | `gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |

> Windows 上原生阶段用 **Git Bash**（`build-a15.ps1` 自动探测 PATH、
> `%ProgramFiles%\Git`、`D:\Program\Git`、`C:\msys64`）+ NDK 29。
> 都找不到时只跑镜像/解包/APEX/素材/prepared/APK，并以退出码 2 列出要补的
> `sh` 命令，不会静默复用旧库。2026-10-30 用 Git Bash 重编
> `bridges/build-jni-register.sh`，产物与既有 `D88FEC7D…` 逐字节一致。

桥接阶段在 API 35 上是否要改源码，取决于阶段 2 的实际编译结果；不要假设 A9 的
`build-*.sh` 原样可用。

---

## 4. 产物与验收

| 产物 | 位置 | 验收 |
| --- | --- | --- |
| 静态 Bionic | `runtime/build/lib/liba15vm-bootstrap.a` | 不含 A9/A10 符号；路径补丁生效 |
| 私有 linker | `runtime/build/bin/source-linker64` | 无未定义符号、无 `DT_NEEDED`、AArch64、SONAME `ld-android.so` |
| libdl | `runtime/build/lib/libdl.so` | 导出 `android_set_application_target_sdk_version@@LIBC_PLATFORM` |
| libc | `runtime/build/lib/libc.so` | 含 `A10VM_ROOTFS` 等映射逻辑、属性区补丁 |
| libm | `runtime/build/lib/libm.so` | 无未定义符号，依赖 `libc.so` |
| 闭包审计 | `runtime/libart-closure.json` | A15 `libart.so` 的未定义符号全部由 API 35 域解析 |

符号闸门（阶段 1 退出条件）与 A9 相同：**A15 `libart.so` 的每个未定义符号
都必须能在 API 35 域的 libc/libdl/libm/ART 闭包里找到定义**，不允许用 shim 掩盖。

---

## 5. 失败时先看哪里

| 现象 | 首先检查 |
| --- | --- |
| `Unsupported source` | 源清单是否混入 CRLF / 拼错路径 |
| `fatal error: 'xxx' file not found` | A15 是否把该文件移到了别的目录（liblog、propertyinfoparser、jemalloc_new） |
| `undefined symbol`（链接） | 静态/共享源清单缺文件，或版本脚本要求了未编入的符号 |
| `version script assignment ... failed` | 版本脚本包含未编入符号；先确认对象是否在链接行 |
| jemalloc 编译错误 | 是否带上了 Android 参数与 `-include async_safe/log.h` |
| linker 配置不生效 | `A10VM_LD_CONFIG` 是否被私有 linker 读取（`patch-linker-a15.py` 是否真的应用） |
| ART 阶段缺符号 | 该符号属于哪个 API 域；不要用 shim 掩盖 |

---

## 6. CI

`.github/workflows/build.yml`：push tag `v*` 或手动触发，在 ubuntu-24.04 上跑
`sh scripts/build.sh`（`A15_SKIP_APK=1`），产出 `prepared/` 的 tar.gz 与
`SHA256SUMS`；tag 触发时自动创建 Release。手动触发可勾选
`rebuild_desktop` 从源码重编 `desktop-gpu-host`。

APK 不在本仓库构建（需要宿主工程的 `:app`）；等 Gradle 外壳补上后，工作流里
那个 AAR 步骤会自动生效。

---

## 7. 不变量

1. A15 产物只允许来自 API 35 源码或 Redroid 15 原文件；
2. `liba15vm-*` 与 `liba9vm-*`、`liba10vm-*` 不交叉；
3. 路径/属性/身份映射只由源码 libc/linker 提供；
4. 任何 helper 进程不得把自己的库预加载进客体进程；
5. ART 自检不过，不启动系统服务。
