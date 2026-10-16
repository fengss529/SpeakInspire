@echo off
chcp 65001 >nul
REM ============================================================
REM 换机器后一键检查环境
REM
REM 打包回来后先跑这个，看哪些要改。
REM 不改local.properties 和 gradle.properties 里的路径，构建会失败。
REM ============================================================

setlocal

set "OK=0"
set "BAD=0"

echo.
echo ============================================
echo   随手记 · 环境检查
echo ============================================
echo.

REM ---------- 1. Java ----------
echo [1/5] Java
where java >nul 2>&1
if errorlevel 1 (
    echo   [X] PATH 里没有 java
    echo       需要 JDK 21。AGP 8.13 不接受 JDK 25。
    set /a BAD+=1
) else (
    for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr "version"') do (
        echo   √ Java %%V
        echo %%V | findstr /I "1\." >nul
        set /a OK+=1
    )
)

REM ---------- 2. SDK 路径 ----------
echo.
echo [2/5] Android SDK 路径
if exist "local.properties" (
    findstr /R "^sdk.dir=" local.properties >nul 2>&1
    if errorlevel 1 (
        echo   [!] local.properties 存在但没有 sdk.dir
        set /a BAD+=1
    ) else (
        for /f "tokens=1,* delims==" %%A in ('findstr /R "^sdk.dir=" local.properties') do (
            set "SDK=%%B"
            set "SDK=!SDK:\:=!"
            set "SDK=!SDK:\\=!"
            if exist "!SDK!\" (
                echo   √ SDK: !SDK!
                set /a OK+=1
            ) else (
                echo   [X] 路径不存在: !SDK!
                echo       改 local.properties 里的 sdk.dir
                set /a BAD+=1
            )
        )
    )
) else (
    echo   [X] 没有 local.properties
    echo       新建它，内容：sdk.dir=你的SDK路径
    echo       注意 Windows 路径里的冒号和反斜杠要转义
    set /a BAD+=1
)

REM ---------- 3. JDK 路径 ----------
echo.
echo [3/5] JDK 路径（gradle.properties）
findstr /R "org.gradle.java.home=" gradle.properties >nul 2>&1
if errorlevel 1 (
    echo   [!] gradle.properties 里没设 java.home
    echo       如果 PATH 里的 java 是 21，可以不管
) else (
    for /f "tokens=1,* delims==" %%A in ('findstr /R "org.gradle.java.home=" gradle.properties') do (
        set "JDK=%%B"
        set "JDK=!JDK:\:=!"
        set "JDK=!JDK:\\=!"
        if exist "!JDK!\bin\java.exe" (
            echo   √ JDK: !JDK!
            set /a OK+=1
        ) else (
            echo   [X] 路径不存在: !JDK!
            echo       改 gradle.properties 里的 org.gradle.java.home
            set /a BAD+=1
        )
    )
)

REM ---------- 4. adb ----------
echo.
echo [4/5] adb
where adb >nul 2>&1
if errorlevel 1 (
    echo   [!] PATH 里没有 adb
    echo       不影响构建，只是装 APK / 看日志不方便
) else (
    echo   √ 找到 adb
    set /a OK+=1
)

REM ---------- 5. 模拟器 ----------
echo.
echo [5/5] 模拟器
if exist "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" (
    echo   √ 在默认位置找到
) else (
    echo   [!] 默认位置没有，可能是装在别处
    echo       只影响模拟器测试，不影响构建
)

REM ---------- 结果 ----------
echo.
echo ============================================
if %BAD% equ 0 (
    echo   看起来没问题。可以试构建了：
    echo.
    echo     gradlew.bat test
    echo.
    echo   注意：gradlew --version 显示的 Launcher JVM 版本
    echo   只反映启动器，判断配置是否生效只能看构建结果。
) else (
    echo   有 %BAD% 处要改，见上面标 [X] 的部分。
    echo.
    echo   改完再跑一次这个脚本。
)
echo ============================================
echo.
pause

endlocal