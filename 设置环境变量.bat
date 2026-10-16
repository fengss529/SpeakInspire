@echo off
REM ============================================
REM SpeakInspire 构建环境变量
REM 以【管理员身份】运行，运行后重开终端
REM
REM 已装：
REM   JDK D:\soft\jdk21  (Temurin 21.0.12.1 LTS)
REM   SDK D:\soft\AndroidSdk  (API 36 / build-tools 36)
REM   Gradle D:\soft\gradle-8.13  (项目用 wrapper，日常无需单独调用)
REM   Android Studio 未装——命令行已可构建，装了只是更方便
REM ============================================

echo [1/4] 设置 JAVA_HOME = D:\soft\jdk21 ...
setx JAVA_HOME "D:\soft\jdk21" /M

echo [2/4] 设置 ANDROID_HOME ...
setx ANDROID_HOME "D:\soft\AndroidSdk" /M

echo [3/4] 设置 ANDROID_SDK_ROOT ...
setx ANDROID_SDK_ROOT "D:\soft\AndroidSdk" /M

echo [4/4] 加入 PATH ...
setx PATH "%PATH%;D:\soft\jdk21\bin;D:\soft\AndroidSdk\platform-tools;D:\soft\AndroidSdk\cmdline-tools\latest\bin" /M

echo.
echo ============================================
echo 完成。请【关闭并重开终端】，然后验证：
echo.
echo   cd /d D:\work2\AI\SpeakInspire
echo   gradlew.bat test
echo.
echo 若失败，可改用完整路径：
echo   cd /d D:\work2\AI\SpeakInspire
echo   set JAVA_HOME=D:\soft\jdk21
echo   gradlew.bat test
echo ============================================
pause