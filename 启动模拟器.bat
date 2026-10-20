@echo off
chcp 65001 >nul
REM ============================================================
REM 启动「随手记」的开发用模拟器（软件渲染）
REM
REM 直接双击这个文件就能用。
REM
REM 为什么需要它：
REM   本机显卡是 Intel Iris Xe，模拟器的硬件加速翻译层与它不兼容。
REM   表现是界面能显示，但 adb 截图截出来是黑图。
REM   用 CPU 软件渲染（swiftshader_indirect）绕开，截图就正常。
REM
REM 如果你只是在模拟器里点着玩、不需要截图：
REM   直接在 Android Studio 里启动 Pixel_7 即可，用不到本脚本。
REM ============================================================

setlocal

set "EMU=D:\soft\AndroidSdk\emulator\emulator.exe"
set "ADB=D:\soft\AndroidSdk\platform-tools\adb.exe"
set "AVD=Pixel_7"
set "APK=D:\work2\AI\SpeakInspire\app\build\outputs\apk\debug\app-debug.apk"

REM ---------- 关键：清掉代理环境变量 ----------
REM
REM 本机设了 HTTP_PROXY / http_proxy 两套（大小写各一），
REM 模拟器启动时会因冲突而静默退出——
REM 表现是「进程起来了但几十秒后消失」，很容易误判成崩溃。
REM 这也是之前「加了参数还是黑屏」的真正原因。

set HTTP_PROXY=
set http_proxy=
set HTTPS_PROXY=
set https_proxy=

REM ---------- 检查环境 ----------

if not exist "%EMU%" (
    echo   [X]找不到模拟器：%EMU%
    echo.
    pause
    exit /b 1
)

if not exist "%ADB%" (
    echo   [X]找不到 adb：%ADB%
    echo.
    pause
    exit /b 1
)

REM ---------- 关掉已有实例并清锁 ----------

tasklist /FI "IMAGENAME eq emulator.exe" 2>nul | find /I "emulator.exe" >nul
if not errorlevel 1 (
    echo   关闭已运行的模拟器...
    taskkill /F /IM emulator.exe /T >nul 2>&1
    taskkill /F /IM qemu-system-x86_64.exe /T >nul 2>&1
    timeout /t 3 /nobreak >nul
)

REM AVD 锁文件没清会导致新实例启动失败
for %%F in ("%USERPROFILE%\.android\avd\%AVD%.avd\*.lock") do (
    if exist "%%F" (
        echo   清理锁文件 %%~nxF
        del "%%F" >nul 2>&1
    )
)

REM ---------- 启动 ----------

echo.
echo   启动 %AVD% （软件渲染）
echo   首次启动慢，请耐心等 2-3 分钟。
echo.
REM -scale 0.4：本机桌面是 1280x720，而 AVD 是 1080x2400 像素。
REM 不缩放的话窗口本身就比屏幕高，只能看到一小截。
start "" "%EMU%" -avd %AVD% -gpu swiftshader_indirect -no-snapshot-load -scale 0.4

REM ---------- 等设备上线 ----------

set /a COUNT=0
:WAIT
timeout /t 5 /nobreak >nul
set /a COUNT+=5
"%ADB%" devices 2>nul | find "device" >nul && goto BOOTING
if %COUNT% lss 300 goto WAIT

echo.
echo   [X]等了5 分钟设备还没上线。
echo      可能原因：
echo      - 代理环境变量没清干净（脚本已处理，若仍失败请查系统环境变量）
echo      - AVD 锁文件冲突（脚本已清理）
echo      - 电脑上还开着别的模拟器实例
echo.
pause
exit /b 1

:BOOTING
echo.
echo   √ 设备已上线，等待系统启动完成...
echo.

set "BOOTED="
set /a BCOUNT=0
:BOOTWAIT
timeout /t 5 /nobreak >nul
set /a BCOUNT+=5
for /f "delims=" %%B in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "BOOTED=%%B"
if "%BOOTED%"=="1" goto READY
if %BCOUNT% lss 300 goto BOOTWAIT

echo.
echo   [!] 系统启动超时，先装 App 试试。
echo.

:READY
REM ---------- 把窗口摆回屏幕内 ----------
REM
REM 模拟器按「缩放后的桌面」计算居中位置，这台机器上算出来的
REM 窗口顶部是 -730，整块窗口在屏幕上方看不见。
REM 进程明明在跑、adb 也连得上，但桌面上一片空白，很容易误判成没启动。
echo.
echo   把模拟器窗口摆回屏幕内...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fix-emulator-window.ps1"

REM ---------- 装 APK ----------

if exist "%APK%" (
    echo   安装 App...
    "%ADB%" install -r "%APK%" 2>nul | find /I "Success" >nul
    if errorlevel 1 (
        echo     [!] 安装失败，若有旧版本请先卸载
    ) else (
        echo     √ 已安装
    )
) else (
    echo   [!] 没找到 APK，跳过安装。
    echo       在 Android Studio 里按 Shift+F10 可直接运行。
)

REM ---------- 启动 App ----------

echo.
echo   启动 App...
"%ADB%" shell am start -n com.hatio.speakinspire/.MainActivity >nul 2>&1

echo.
echo   ------------------------------------------
echo   模拟器窗口会自己弹出，直接点着玩就行。
echo.
echo   要截图的话用这条命令：
echo     "%ADB%" exec-out screencap -p ^> D:\work2\AI\outputs\shot.png
echo.
echo   注意：软件渲染下系统较慢，偶尔会弹
echo   "System UI isn't responding"，点 Wait 等它恢复即可。
echo.
echo   按任意键关闭本窗口（不影响模拟器）。
echo   ------------------------------------------
echo.
pause >nul

endlocal