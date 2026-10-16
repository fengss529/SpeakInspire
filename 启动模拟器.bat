@echo off
chcp 65001 >nul
REM ============================================================
REM 启动「随手记」的开发用模拟器（软件渲染）
REM
REM 直接双击这个文件就能用。
REM
REM 为什么需要它：
REM   本机显卡是 Intel Iris Xe，Android 模拟器的硬件加速
REM   翻译层与它不兼容。表现为界面其实正常，但截图是黑的。
REM   用 CPU 软件渲染绕开，截图就正常了。
REM   代价是界面动画略慢，日常开发够用。
REM
REM 如果你只是想在模拟器里点着玩、不需要截图，
REM   直接在 Android Studio 里启动 Pixel_7 即可，用不到本脚本。
REM ============================================================

setlocal

set "EMU=D:\soft\AndroidSdk\emulator\emulator.exe"
set "ADB=D:\soft\AndroidSdk\platform-tools\adb.exe"
set "AVD=Pixel_7"
set "APK=D:\work2\AI\SpeakInspire\app\build\outputs\apk\debug\app-debug.apk"

REM ---------- 检查环境 ----------

if not exist "%EMU%" (
    echo   [X]找不到模拟器：%EMU%
    echo      请检查路径是否变了。
    pause
    exit /b 1
)

if not exist "%ADB%" (
    echo   [X]找不到 adb：%ADB%
    pause
    exit /b 1
)

REM ---------- 关掉已有实例 ----------

tasklist /FI "IMAGENAME eq emulator.exe" 2>nul | find /I "emulator.exe" >nul
if not errorlevel 1 (
    echo   关闭已运行的模拟器...
    taskkill /F /IM emulator.exe /T >nul 2>&1
    taskkill /F /IM qemu-system-x86_64.exe /T >nul 2>&1
    timeout /t 2 /nobreak >nul
)

REM ---------- 启动 ----------

echo.
echo   启动 %AVD% （软件渲染，首次可能要 1-2 分钟）
echo.
start "" "%EMU%" -avd %AVD% -gpu swiftshader_indirect -no-snapshot-load

REM ---------- 等设备上线 ----------

set /a COUNT=0
:WAIT
timeout /t 2 /nobreak >nul
set /a COUNT+=1
"%ADB%" devices 2>nul | find "device" >nul && goto READY
if %COUNT% lss 90 goto WAIT

echo.
echo   [X]等待超时。可能的��法：
echo      - Android Studio 里也开着模拟器，关掉再试
echo      - 模拟器窗口里被卡住了，看它有没有弹出黑屏或报错
echo.
pause
exit /b 1

:READY
echo.
echo   √ 模拟器已就绪
echo.

REM ---------- 装APK ----------

if exist "%APK%" (
    echo   正在安装App...
    "%ADB%" install -r "%APK%" >nul 2>&1
    if errorlevel 1 (
        echo     [X] 安装失败，可能有旧版本冲突，手动卸载后再试
    ) else (
        echo     √ 已安装
    )
    echo.
) else (
    echo   [!] 没找到 APK，跳过安装。
    echo       在 Android Studio 里按Shift+F10 就能直接跑。
    echo.
)

REM ---------- 启动 App ----------

"%ADB%" shell am start -n com.hatio.speakinspire/.MainActivity >nul 2>&1

echo   App 已启动。切换到模拟器窗口即可看到。
echo.
echo   小提示：截图用这个命令（软件渲染下才正常）
echo     "%ADB%" exec-out screencap -p ^> D:\work2\AI\outputs\shot.png
echo.
echo   按任意键关闭本窗口（不会关闭模拟器）
echo.
pause >nul

endlocal