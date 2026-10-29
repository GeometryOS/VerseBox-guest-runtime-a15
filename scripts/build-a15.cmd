@echo off
rem One-command Android 15 build on Windows.
rem
rem PowerShell's default execution policy refuses unsigned .ps1 files, so this
rem wrapper runs the same script with -ExecutionPolicy Bypass for this process
rem only. It probes PowerShell 7 (pwsh), then the in-box Windows PowerShell by
rem absolute path (not every PATH includes WindowsPowerShell\v1.0), then the
rem bare name. Arguments are forwarded: -Force, -SkipApk.
setlocal
set "PS="
where /q pwsh && set "PS=pwsh"
if not defined PS if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not defined PS where /q powershell && set "PS=powershell"
if not defined PS (
    echo build-a15.cmd: no PowerShell found ^(pwsh or Windows PowerShell^) 1>&2
    exit /b 1
)
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-a15.ps1" %*
exit /b %ERRORLEVEL%
