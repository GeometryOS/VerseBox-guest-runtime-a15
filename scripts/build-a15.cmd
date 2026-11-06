@echo off
rem ============================================================================
rem build-a15.cmd - one-command Android 15 build on Windows.
rem
rem What it produces (two product chains, see docs/rom-package/11 in the app repo):
rem   prepared -> ROM package (dist\versebox-<id>-<version>.zip) -> renderer -> APK
rem
rem Arguments (forwarded as-is to build-a15.ps1):
rem   -Force  -SkipApk  -SkipRom  -RomVersion <ver>  -LibMode <apk|rom|both>  -RomOut <path>
rem   -SelfTest   verify the failure banner + transcript only (throws on purpose)
rem   -PlanOnly   print what would run and exit 0 (no heavy step). Use it to check
rem               whether this clone stops after the ROM package.
rem
rem Why this wrapper exists: double-clicking opens a console that closes the
rem moment the script ends, so a failure used to disappear without a trace.
rem This file always prints the exit code and the transcript path, then keeps the
rem window open (pause on failure, short grace on success).
rem Set BUILD_NO_PAUSE=1 for CI / scripted use.
rem ============================================================================
setlocal EnableExtensions
set "HERE=%~dp0"
set "LOGDIR=%HERE%build-logs"
set "RC=0"

rem -- find PowerShell ---------------------------------------------------------
set "PS="
where /q pwsh && set "PS=pwsh"
if not defined PS if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not defined PS where /q powershell && set "PS=powershell"
if not defined PS goto :nops

echo [build-a15] powershell : %PS%
echo [build-a15] module     : %HERE%..
echo [build-a15] logs       : %LOGDIR%
echo [build-a15] args       : %*
echo.
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%HERE%build-a15.ps1" %*
set "RC=%ERRORLEVEL%"
goto :finish

:nops
echo [build-a15] ERROR: no PowerShell found (pwsh or Windows PowerShell).
echo [build-a15] Install PowerShell 7, or make sure Windows PowerShell is present.
set "RC=1"

:finish
rem -- newest transcript ------------------------------------------------------
set "LOGFILE="
if exist "%LOGDIR%" for /f "delims=" %%f in ('dir /b /o-d "%LOGDIR%\build-a15-*.log" 2^>nul') do if not defined LOGFILE set "LOGFILE=%LOGDIR%\%%f"
echo.
if "%RC%"=="0" (
    echo [build-a15] OK
) else (
    echo [build-a15] FAILED - exit code %RC%
    echo [build-a15] the reason is in the red block above; full transcript:
)
if defined LOGFILE echo [build-a15] %LOGFILE%
if not defined LOGFILE echo [build-a15] no transcript found - copy the output above when reporting.

rem -- keep the window open ---------------------------------------------------
if "%BUILD_NO_PAUSE%"=="1" goto :done
if not "%RC%"=="0" (
    echo.
    echo Press any key to close this window . . .
    pause >nul
    goto :done
)
echo.
echo This window closes in 8 seconds (press any key to close now).
timeout /t 8 >nul 2>nul

:done
endlocal & exit /b %RC%
