@echo off
REM ============================================================
REM 启动模拟器（软件渲染）
REM
REM 为什么需要这个脚本：
REM   本机显卡是 Intel Iris Xe，Android 模拟器的
REM   "Google (Intel)" 硬件加速翻译层与它不兼容，
REM   表现为界面实际正常（uiautomator 能读到完整界面树）
REM   但 screencap 截出来是黑图。
REM
REM   用 swiftshader_indirect（CPU 软件渲染）绕过这层翻译，
REM   截屏就正常了。代价是渲染慢一点，日常开发够用。
REM ============================================================

setlocal

set EMU=D:\soft\AndroidSdk\emulator\emulator.exe
set AVD=Pixel_7

REM 先关掉已有的实例
taskkill /F /IM emulator.exe /T >nul 2>&1
taskkill /F /IM qemu-system-x86_64.exe /T >nul 2>&1
timeout /t 2 /nobreak >nul

echo.
echo   启动 %AVD%（软件渲染）...
echo   首次启动可能要1-2 分钟。
echo.

start "" "%EMU%" -avd %AVD% -gpu swiftshader_indirect -no-snapshot-load

REM 等设备上线
set ADB=D:\soft\AndroidSdk\platform-tools\adb.exe
set /a COUNT=0
:WAIT
timeout /t 2 /nobreak >nul
set /a COUNT+=1
"%ADB%" devices | findstr "device" >nul && goto READY
if %COUNT% lss 60 goto WAIT

echo   等待超时，试试手动启动。
goto END

:READY
echo   就绪。
echo.
echo   打开 App：adb shell am start -n com.hatio.speakinspire/.MainActivity
echo.

:END
endlocal