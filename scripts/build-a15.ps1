# One-command Android 15 (API 35) build for Windows.
#
#   .\guest-runtime-a15\scripts\build-a15.ps1
#   .\guest-runtime-a15\scripts\build-a15.ps1 -Force             # rebuild native stages
#   .\guest-runtime-a15\scripts\build-a15.ps1 -SkipApk           # 不出 APK（ROM 包照出）
#   .\guest-runtime-a15\scripts\build-a15.ps1 -SkipRom           # 只出运行时目录 prepared，不产 ROM 包
#   .\guest-runtime-a15\scripts\build-a15.ps1 -RomVersion 1.1.0 # 指定 ROM 版本
#   .\guest-runtime-a15\scripts\build-a15.ps1 -LibMode rom      # 客体 .so 下沉到 ROM（需先跑 04 §3.2 实验）
#   .\guest-runtime-a15\scripts\build-a15.ps1 -SelfTest         # 只验证失败摘要与日志落盘（故意报错）
#   .\guest-runtime-a15\scripts\build-a15.ps1 -PlanOnly         # 只打印「会不会停在 ROM 包」并退出（不跑重活）
#
# 产物链是两条，别混：**ROM 包**只依赖 prepared（由 rom/pack-rom.py 产出到 dist/），
# **APK** 由 Gradle 产出。默认两条都出；-SkipRom / -SkipApk 各自单独关掉一条。
#
# What runs here natively: image download, rootfs/APEX extraction, boot-animation
# asset, prepared, APK. The runtime (linker/libc/libdl/libm) and the bridges are
# POSIX shell + NDK builds; this script runs them when a POSIX shell is available
# (Git Bash / MSYS / Linux) and refuses with an explicit list when it is not,
# instead of silently producing a prepared tree with stale native libraries.
#
# Exit codes: 0 ok, 1 failure, 2 native rebuild needed but no POSIX shell.
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SkipApk,
    # 只生成运行时目录（prepared），不产出 ROM 包。
    [switch]$SkipRom,
    # ROM 包的版本号；缺省取自 rom/rom.properties.yaml 的 defaultRomVersion。
    [string]$RomVersion = "",
    # 客体 .so 的承载方式：apk / rom / both；缺省取自述文件。
    [ValidateSet("", "apk", "rom", "both")]
    [string]$LibMode = "",
    # ROM 包输出路径；缺省 <runtime>/dist/versebox-<id>-<version>.zip。
    [string]$RomOut = "",
    # 只猜一遍"这一步会怎么走"并退出（不跑任何重活）：
    # 用来回答"只 clone 运行时仓库时，构建会不会停在 ROM 包"。
    [switch]$PlanOnly,
    # 只验证「失败摘要 + 日志落盘」这条路径（故意抛一个错），不跑真实构建。
    # 双击 build-a15.cmd 报错时，用它确认你看到的是完整信息，而不是"一闪就没"。
    [switch]$SelfTest
)
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------- 失败也要看得见
# 双击 build-a15.cmd 时，窗口会在脚本结束的瞬间关掉，"报错了但看不到原因"就是这么来的。
# 这里做三件事，缺一不可：
#   1) 全部输出同时落进 scripts/build-logs/build-a15-<时间>.log（Start-Transcript）；
#   2) 任何终止性错误由下面的 trap 打成一段可读摘要（卡在哪一步 + 原因 + 日志路径）；
#   3) build-a15.cmd 在失败时 pause，窗口不会一闪就没（CI 用 BUILD_NO_PAUSE=1 关掉）。
$script:BuildLogDir = Join-Path $PSScriptRoot "build-logs"
$script:BuildLog = Join-Path $script:BuildLogDir ("build-a15-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$script:CurrentStep = "（还没开始）"
try { New-Item -ItemType Directory -Force -Path $script:BuildLogDir | Out-Null } catch { }
try { Start-Transcript -LiteralPath $script:BuildLog | Out-Null }
catch { Write-Host ("（日志落盘不可用：" + $_.Exception.Message + "）") -ForegroundColor Yellow }

trap {
    $reason = $_.Exception.Message
    if (-not $reason) { $reason = ($_.Exception.GetType().Name) }
    Write-Host ""
    Write-Host "============== build-a15 FAILED ==============" -ForegroundColor Red
    Write-Host ("卡在步骤：" + $script:CurrentStep) -ForegroundColor Red
    Write-Host ("原因：" + $reason) -ForegroundColor Red
    Write-Host ("完整日志：" + $script:BuildLog) -ForegroundColor Yellow
    Write-Host "下一句怎么查：看上面日志里最后 20 行；不要在别的目录重跑。" -ForegroundColor Yellow
    Write-Host "==============================================" -ForegroundColor Red
    try { Stop-Transcript | Out-Null } catch { }
    exit 1
}
$module = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$root = (Resolve-Path (Join-Path $module "..")).Path
Set-Location $module

function Step([string]$text) {
    # 记下当前步骤：失败摘要里会说"卡在哪一步"，否则只有一句异常信息。
    $script:CurrentStep = $text
    Write-Host ""
    Write-Host "== $text" -ForegroundColor Cyan
}

# APK（以及宿主的桌面渲染器）属于 **app 仓库**。第三方只 clone 本仓库时，构建必须
# 干净地停在 ROM 包，而不是跑到最后抛一个 "gradlew 找不到"。
# 判据是「app 工程的两个标志文件都在」，不是「父目录存在」——clone 到任意目录都成立。
$appGradle = Join-Path $root "gradlew.bat"
$appSettings = Join-Path $root "settings.gradle.kts"
$hasAppRepo = (Test-Path -LiteralPath $appGradle) -and (Test-Path -LiteralPath $appSettings)

# 失败路径自检：不跑真实流水线，只验证「摘要 + 日志」这条链（CI 与双击排障都能用）。
if ($SelfTest) {
    Step "SelfTest: 故意失败，验证失败摘要与日志落盘"
    throw "SelfTest: 这是故意触发的错误（用来验证失败摘要/日志是否可用）"
}

# 只打印计划，不跑重活：回答"只 clone 运行时仓库时会不会停在 ROM 包"。
if ($PlanOnly) {
    Step "PlanOnly"
    Write-Host ("  module        : " + $module)
    Write-Host ("  root          : " + $root)
    Write-Host ("  app 仓库       : " + $(if ($hasAppRepo) { "找到（" + $appGradle + "）" } else { "未找到 -> 构建将停在 ROM 包" }))
    $romPlan = if ($RomOut) { $RomOut } else { Join-Path $module "dist" }
    Write-Host ("  ROM 包         : " + $(if ($SkipRom) { "跳过（-SkipRom）" } else { $romPlan }))
    Write-Host ("  APK           : " + $(if (-not $hasAppRepo) { "跳过（没有 app 仓库）" } elseif ($SkipApk) { "跳过（-SkipApk）" } else { (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk") }))
    Write-Host "  什么都没有执行（PlanOnly）。" -ForegroundColor Cyan
    try { Stop-Transcript | Out-Null } catch { }
    exit 0
}

$python = $null
foreach ($candidate in @($env:PYTHON, "python", "python3")) {
    if (-not $candidate) { continue }
    try {
        $version = & $candidate -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null
        if ($LASTEXITCODE -eq 0 -and [version]$version -ge [version]"3.8") { $python = $candidate; break }
    } catch { }
}
if (-not $python) { throw "No Python 3.8+ found. Set PYTHON to a real interpreter." }
Step "python: $python ($(& $python --version))"

# 1. image -------------------------------------------------------------------
if ($Force -or -not (Test-Path "downloads/manifest.json")) {
    Step "fetch redroid 15 arm64 image (resumable, verifies every layer digest)"
    & $python "scripts/fetch-redroid15.py"
    if ($LASTEXITCODE -ne 0) { throw "fetch-redroid15.py failed" }
} else {
    Step "image already downloaded (downloads/manifest.json)"
}

# 2. rootfs ------------------------------------------------------------------
if ($Force -or -not (Test-Path "rootfs-index.json")) {
    Step "extract rootfs"
    & $python "scripts/extract-rootfs.py"
    if ($LASTEXITCODE -ne 0) { throw "extract-rootfs.py failed" }
} else {
    Step "rootfs already extracted (rootfs-index.json)"
}

# 3. APEX --------------------------------------------------------------------
if ($Force -or -not (Test-Path "rootfs/apex/com.android.art/.expanded")) {
    Step "expand APEX payloads"
    & $python "scripts/extract-apex.py"
    if ($LASTEXITCODE -ne 0) { throw "extract-apex.py failed" }
} else {
    Step "APEX already expanded"
}

# 4. native runtime + bridges ------------------------------------------------
$native = [ordered]@{
    "runtime/build/lib/liba15vm-bootstrap.a"    = "runtime\build-static.sh"
    "runtime/build/bin/source-linker64"         = "runtime\build-linker.sh"
    "runtime/build/lib/libc.so"                 = "runtime\build-libc.sh"
    "runtime/build/lib/libm.so"                 = "runtime\build-libm.sh"
    "bridges/lib/liba15vm-property-client.so"   = "bridges\build-property.sh"
    "bridges/lib/liba15binder.so"               = "bridges\build-binder.sh"
    "bridges/lib/liba15vm-guest-devices.so"     = "bridges\build-devices.sh"
    "bridges/lib/liba15vm-guest-log.so"         = "bridges\build-guest-log.sh"
    "bridges/lib/liba15vm-jni-register.so"      = "bridges\build-jni-register.sh"
    # host-devices comes from build-host-hal.sh; prepare-a15.py requires it.
    "bridges/bin/host-devices"                  = "bridges\build-host-hal.sh"
    "bridges/graphics/lib/libGLESv2_emulation.so" = "bridges\build-graphics-a15.sh"
    # Legacy audio HAL that replaces the image's silent stub (routes to audio.sock).
    "bridges/lib/audio.primary.default.so" = "bridges\build-audio-hal.sh"
}
# Pinned host GPU endpoint (prebuilt/arm64/desktop-gpu-host, sha256-checked).
# The sources and the fetch/build scripts are vendored under third_party/graphics;
# rebuilding it needs the A10 container pipeline, so the binary is pinned.
Step "desktop GPU endpoint (pinned)"
$prebuilt = Join-Path $module "prebuilt\arm64\desktop-gpu-host"
$installed = Join-Path $module "bridges\bin\desktop-gpu-host"
$expected = (Get-Content (Join-Path $module "prebuilt\arm64\desktop-gpu-host.sha256") -Raw).Trim()
if (-not (Test-Path $prebuilt)) { throw "missing $prebuilt" }
$actual = (Get-FileHash $prebuilt -Algorithm SHA256).Hash
if ($actual -ne $expected) { throw "desktop-gpu-host sha256 mismatch: $actual" }
$current = if (Test-Path $installed) { (Get-FileHash $installed -Algorithm SHA256).Hash } else { "" }
if ($current -ne $expected) {
    New-Item -ItemType Directory -Force -Path (Split-Path $installed) | Out-Null
    Copy-Item -LiteralPath $prebuilt -Destination $installed -Force
    Write-Host "desktop-gpu-host: installed ($actual)"
} else {
    Write-Host "desktop-gpu-host: up to date"
}

$missing = @()
foreach ($product in $native.Keys) {
    if ($Force -or -not (Test-Path $product)) { $missing += $native[$product] }
}
if ($missing.Count -gt 0) {
    # Git for Windows is the usual POSIX shell on a Windows dev box and is often
    # installed outside PATH (e.g. D:\Program\Git). Probe PATH first, then the
    # standard locations, before declaring the native stages unavailable.
    $shell = $null
    $candidates = @()
    foreach ($name in @("bash", "sh")) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { $candidates += $command.Source }
    }
    $candidates += @(
        (Join-Path ${env:ProgramFiles} "Git\bin\bash.exe"),
        (Join-Path ${env:ProgramFiles} "Git\usr\bin\sh.exe"),
        "D:\Program\Git\bin\bash.exe",
        "D:\Program\Git\usr\bin\sh.exe",
        "C:\msys64\usr\bin\bash.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { $shell = $candidate; break }
    }
    if (-not $shell) {
        Write-Host ""
        Write-Host "Native stages are missing and no POSIX shell (bash/sh) is in PATH:" -ForegroundColor Yellow
        foreach ($script in $missing) { Write-Host "  sh $script" }
        Write-Host "Run them in Git Bash / MSYS / Linux (needs the Android NDK 29)," -ForegroundColor Yellow
        Write-Host "then rerun this script with -SkipApk or without it." -ForegroundColor Yellow
        exit 2
    }
    Step "native stages via $shell (NDK)"
    if (($missing -contains "bridges\build-graphics-a15.sh") -and
        -not (Test-Path (Join-Path $module "third_party/graphics/a9/upstream/goldfish"))) {
        Write-Host "-- scripts/fetch-graphics-sources.sh"
        & $shell "scripts/fetch-graphics-sources.sh"
        if ($LASTEXITCODE -ne 0) { throw "fetch-graphics-sources.sh failed" }
    }
    foreach ($script in $missing) {
        Write-Host "-- $script"
        & $shell ($script -replace "\\", "/")
        if ($LASTEXITCODE -ne 0) { throw "$script failed" }
    }
} else {
    Step "native runtime and bridges already built"
}

# 5. assets + prepared --------------------------------------------------------
Step "boot animation asset"
& $python "scripts/make-bootanimation.py"
if ($LASTEXITCODE -ne 0) { throw "make-bootanimation.py failed" }

Step "prepared (manifest + assets + jniLibs)"
& $python "scripts/prepare-a15.py"
if ($LASTEXITCODE -ne 0) { throw "prepare-a15.py failed" }

# 5c. ROM package --------------------------------------------------------------
# 这是「编 ROM」这条产物链的终点，属于运行时仓库（rom/），与下面的 Gradle APK
# 没有任何依赖关系：ROM 只读 prepared。第三方定制者只跑这一步就能出包。
if ($SkipRom) {
    Step "SkipRom: 只生成运行时目录（prepared），不产出 ROM 包"
} else {
    Step "ROM package (rom/pack-rom.py -> dist/)"
    $romArgs = @("rom/pack-rom.py", "--prepared", "prepared")
    if ($RomVersion) { $romArgs += @("--rom-version", $RomVersion) }
    if ($LibMode) { $romArgs += @("--lib-mode", $LibMode) }
    if ($RomOut) { $romArgs += @("--out", $RomOut) }
    & $python @romArgs
    if ($LASTEXITCODE -ne 0) { throw "rom/pack-rom.py failed" }
}

# ---------------------------------------------------------------- 产物摘要
# 三条出口（ROM-only / -SkipApk / 全流程）都打印同一份摘要，避免"出没出包"靠猜。
function Show-Artifacts([bool]$withApk, [string]$headline) {
    Write-Host ""
    Write-Host $headline -ForegroundColor Green
    if ($withApk) {
        Write-Host ("  APK      : " + (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk")) -ForegroundColor Green
    }
    if (-not $SkipRom) {
        $romShown = if ($RomOut) { $RomOut } else { Join-Path $module "dist" }
        Write-Host ("  ROM 包   : " + $romShown) -ForegroundColor Green
    } else {
        Write-Host ("  运行时目录: " + (Join-Path $module "prepared") + "  (SkipRom)") -ForegroundColor Green
    }
    Write-Host ("  日志      : " + $script:BuildLog) -ForegroundColor Cyan
    try { Stop-Transcript | Out-Null } catch { }
}

# 6. APK ---------------------------------------------------------------------
# $hasAppRepo 在脚本开头已经算过（见 "APK 属于 app 仓库" 那段注释）。
if (-not $hasAppRepo) {
    Step "no app repository found at $root"
    Write-Host ""
    Write-Host "这是 ROM-only 构建：本仓库只产出运行时目录与 ROM 包。" -ForegroundColor Yellow
    Write-Host ("  找不到 " + $appGradle + " 与 " + $appSettings) -ForegroundColor Yellow
    Write-Host "  APK 与宿主渲染器属于 app 仓库（docs/rom-package/11 §2）。" -ForegroundColor Yellow
    Write-Host "  要出 APK：把本仓库放进 app 工程里再跑，或直接跑 app 仓库的 Gradle。" -ForegroundColor Yellow
    Show-Artifacts -withApk $false -headline "ROM 构建完成（未构建 APK）"
    exit 0
}

if ($SkipApk) {
    Step "SkipApk: stopping before Gradle"
    Show-Artifacts -withApk $false -headline "运行时目录与 ROM 包已就绪（未出 APK）"
    exit 0
}
# 6a. APK 侧旧格式 payload ----------------------------------------------------
# 客体 .so 不进 APK 的 lib/arm64-v8a：旧格式（打进 APK）把它们放在
# assets/guest-runtime-a15/lib/**（清单 files[].libPath），新格式放在 ROM 的
# payload/lib/**。Gradle 消费的是这里产出的 prepared-apk，不是 prepared。
Step "APK payload (rom/stage-apk-payload.py -> prepared-apk/)"
& $python "rom/stage-apk-payload.py" --prepared "prepared" --out "prepared-apk"
if ($LASTEXITCODE -ne 0) { throw "rom/stage-apk-payload.py failed" }

# 5b. desktop window renderer, built FROM SOURCE ----------------------------------
# Sources: third_party/graphics/container/{window,desktop,native} in THIS repository
# (the same tree commit 90cb89e patched for the GLES1 crash). There is no pinned
# fallback on purpose: when no build step compiled this source, the packaged renderer
# silently stayed at the old one and every ES1 app died with
# "createContext failed: EGL_BAD_ALLOC" (AIDA64, code=137).
Step "desktop window renderer (from source)"
$rendererShell = $null
foreach ($candidate in @((Get-Command bash -ErrorAction SilentlyContinue | Select-Object -First 1).Source) + @(
        "$env:ProgramFiles\Git\bin\bash.exe",
        "D:\Program\Git\bin\bash.exe", "/usr/bin/bash", "/bin/bash")) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) { $rendererShell = $candidate; break }
}
if (-not $rendererShell) { throw "desktop window renderer needs bash in PATH (it is compiled from source)" }
& $rendererShell "scripts/build-desktop-window.sh"
if ($LASTEXITCODE -ne 0) { throw "scripts/build-desktop-window.sh failed" }
$rendererRoot = "guest-runtime-a15/artifacts/native-display"
if (-not (Test-Path (Join-Path $module "artifacts/native-display/jniLibs/arm64-v8a/liba15vm-desktop-window.so"))) {
    throw "desktop renderer was not produced under $rendererRoot"
}
Write-Host "  renderer: $rendererRoot (compiled from source)"

Step "APK (app-debug.apk)"
Set-Location $root
& (Join-Path $root "gradlew.bat") ":app:assembleDebug" "-PnativeDisplayPrepared=$rendererRoot"
if ($LASTEXITCODE -ne 0) { throw "gradle :app:assembleDebug failed" }
Show-Artifacts -withApk $true -headline "A15 pipeline complete"