# One-command Android 15 (API 35) build for Windows.
#
#   .\guest-runtime-a15\scripts\build-a15.ps1
#   .\guest-runtime-a15\scripts\build-a15.ps1 -Force        # rebuild native stages
#   .\guest-runtime-a15\scripts\build-a15.ps1 -SkipApk      # stop after prepared
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
    [switch]$SkipApk
)
$ErrorActionPreference = "Stop"
$module = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$root = (Resolve-Path (Join-Path $module "..")).Path
Set-Location $module

function Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

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

# 6. APK ---------------------------------------------------------------------
if ($SkipApk) {
    Step "SkipApk: stopping before Gradle"
    exit 0
}
Step "APK (app-debug.apk)"
Set-Location $root
& (Join-Path $root "gradlew.bat") ":app:assembleDebug"
if ($LASTEXITCODE -ne 0) { throw "gradle :app:assembleDebug failed" }
Write-Host ""
Write-Host "A15 pipeline complete: $root\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
