@echo off
REM ===========================================================================
REM  Build a native Windows installer (.exe) for Mythosaur RE using jpackage.
REM  A self-contained Java runtime is bundled, so end users do NOT need Java.
REM  Run this ON a Windows machine (jpackage cannot cross-build).
REM
REM  Requirements:
REM    - JDK 21+ (provides jpackage) on PATH
REM    - Inno Setup 6+ (https://jrsoftware.org/isdl.php) on PATH  -> for --type exe
REM      (or use --type msi instead, which needs the WiX Toolkit)
REM
REM  Output: dist\Mythosaur RE-<version>.exe
REM ===========================================================================
setlocal
cd /d "%~dp0\.."

set VERSION=1.0.0
set MAIN_JAR=mythosaur-jvm-%VERSION%.jar

echo ==^> Building application image (gradle installDist)...
call gradlew.bat clean installDist -q || goto :error

echo ==^> Running jpackage (.exe)...
if exist dist rmdir /s /q dist
mkdir dist

jpackage ^
  --type exe ^
  --name "Mythosaur RE" ^
  --app-version %VERSION% ^
  --description "Dedicated open-source APK reverse-engineering IDE" ^
  --vendor "durgeshkt" ^
  --copyright "Mythosaur RE - MIT License" ^
  --input build\install\mythosaur-jvm\lib ^
  --main-jar %MAIN_JAR% ^
  --main-class com.mythosaur.Main ^
  --java-options -Xmx6g ^
  --java-options -XX:+UseG1GC ^
  --java-options -XX:MaxMetaspaceSize=512m ^
  --java-options -Dsun.java2d.opengl=false ^
  --add-modules "java.base,java.compiler,java.desktop,java.sql,java.logging,java.naming,java.xml,java.management,jdk.jdi,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs" ^
  --jlink-options "--strip-debug --no-header-files --no-man-pages" ^
  --license-file src\main\resources\TERMS.txt ^
  --icon packaging\icon.ico ^
  --win-shortcut ^
  --win-menu ^
  --win-menu-group "Mythosaur" ^
  --win-dir-chooser ^
  --win-per-user-install ^
  --dest dist || goto :error

echo.
echo ==^> Done. Installer is in the dist\ folder.
dir dist
goto :eof

:error
echo BUILD FAILED (errorlevel %errorlevel%)
exit /b %errorlevel%
