@echo off
setlocal EnableExtensions EnableDelayedExpansion

title JavaClawBot Packaging Tool

echo.
echo =========================================================
echo   JavaClawBot Windows EXE Packaging Tool
echo   Ultra Stable English-Only Version
echo =========================================================
echo.

REM =========================================================
REM Configuration
REM =========================================================

set "JAVA_HOME=C:\Program Files\Java17\jdk-17"
set "JAVA_BIN=%JAVA_HOME%\bin"

set "JAVA_CMD=%JAVA_BIN%\java.exe"
set "JLINK_CMD=%JAVA_BIN%\jlink.exe"
set "JPACKAGE_CMD=%JAVA_BIN%\jpackage.exe"

set "APP_NAME=javaclawbot"
set "APP_VERSION=1.0.0"
set "VENDOR=JavaClawBot"
set "DESCRIPTION=JavaClawBot - AI Assistant"

set "MAIN_JAR_NAME=javaclawbot.jar"
set "MAIN_CLASS=gui.JavaClawBotGUI"

set "BASE_URL=D:\open_code\pkg_exe"
set "JAR_FILE=%BASE_URL%\javaclawbot.jar"
set "OUTPUT_DIR=%BASE_URL%\dist"
set "RUNTIME_DIR=%BASE_URL%\jre"
set "TEMP_INPUT_DIR=%BASE_URL%\temp_package"

REM =========================================================
REM Runtime Java options for packaged application
REM =========================================================

set "JAVA_OPT_1=-Dfile.encoding=UTF-8"
set "JAVA_OPT_2=-Dsun.jnu.encoding=UTF-8"
set "JAVA_OPT_3=-Duser.language=en"
set "JAVA_OPT_4=-Duser.country=US"

REM =========================================================
REM Ultra-stable module set
REM =========================================================

set "MODULES=java.se,java.net.http,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.management,jdk.management.agent,jdk.jdwp.agent,jdk.jfr,jdk.jshell,jdk.httpserver,jdk.accessibility,jdk.attach,jdk.compiler,jdk.jartool,jdk.jdi,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.xml.dom"

echo [DEBUG] Working directory : %cd%
echo [DEBUG] JAVA_HOME         : %JAVA_HOME%
echo [DEBUG] JAVA_BIN          : %JAVA_BIN%
echo [DEBUG] JAVA_CMD          : %JAVA_CMD%
echo [DEBUG] JLINK_CMD         : %JLINK_CMD%
echo [DEBUG] JPACKAGE_CMD      : %JPACKAGE_CMD%
echo [DEBUG] APP_NAME          : %APP_NAME%
echo [DEBUG] APP_VERSION       : %APP_VERSION%
echo [DEBUG] VENDOR            : %VENDOR%
echo [DEBUG] DESCRIPTION       : %DESCRIPTION%
echo [DEBUG] MAIN_JAR_NAME     : %MAIN_JAR_NAME%
echo [DEBUG] MAIN_CLASS        : %MAIN_CLASS%
echo [DEBUG] JAR_FILE          : %JAR_FILE%
echo [DEBUG] OUTPUT_DIR        : %OUTPUT_DIR%
echo [DEBUG] RUNTIME_DIR       : %RUNTIME_DIR%
echo [DEBUG] TEMP_INPUT_DIR    : %TEMP_INPUT_DIR%
echo [DEBUG] JAVA_OPT_1        : %JAVA_OPT_1%
echo [DEBUG] JAVA_OPT_2        : %JAVA_OPT_2%
echo [DEBUG] JAVA_OPT_3        : %JAVA_OPT_3%
echo [DEBUG] JAVA_OPT_4        : %JAVA_OPT_4%
echo [DEBUG] MODULES           : %MODULES%
echo.

REM =========================================================
REM Validation
REM =========================================================

if not exist "%JAVA_HOME%" (
    echo [ERROR] JAVA_HOME does not exist:
    echo         %JAVA_HOME%
    pause
    exit /b 1
)

if not exist "%JAVA_CMD%" (
    echo [ERROR] java.exe was not found:
    echo         %JAVA_CMD%
    pause
    exit /b 1
)

if not exist "%JLINK_CMD%" (
    echo [ERROR] jlink.exe was not found:
    echo         %JLINK_CMD%
    pause
    exit /b 1
)

if not exist "%JPACKAGE_CMD%" (
    echo [ERROR] jpackage.exe was not found:
    echo         %JPACKAGE_CMD%
    pause
    exit /b 1
)

if not exist "%JAR_FILE%" (
    echo [ERROR] Main JAR file was not found:
    echo         %JAR_FILE%
    pause
    exit /b 1
)

echo =========================================================
echo Java version
echo =========================================================
"%JAVA_CMD%" -version
if errorlevel 1 (
    echo [ERROR] Failed to run java -version
    pause
    exit /b 1
)
echo.

echo =========================================================
echo jlink version
echo =========================================================
"%JLINK_CMD%" --version
if errorlevel 1 (
    echo [ERROR] Failed to run jlink --version
    pause
    exit /b 1
)
echo.

echo =========================================================
echo jpackage version
echo =========================================================
"%JPACKAGE_CMD%" --version
if errorlevel 1 (
    echo [ERROR] Failed to run jpackage --version
    pause
    exit /b 1
)
echo.

REM =========================================================
REM Force cleanup before packaging
REM =========================================================

echo [STEP] Cleaning old packaging directories...
echo.

if exist "%OUTPUT_DIR%" (
    echo [INFO] Removing old output directory:
    echo        %OUTPUT_DIR%
    rmdir /s /q "%OUTPUT_DIR%"
    if exist "%OUTPUT_DIR%" (
        echo [ERROR] Failed to remove output directory.
        echo [ERROR] Another process may still be using files in this folder.
        echo [ERROR] Please close the installed app, installer, terminal, or Explorer window using it.
        pause
        exit /b 1
    )
    echo [OK] Output directory removed.
    echo.
)

if exist "%RUNTIME_DIR%" (
    echo [INFO] Removing old runtime directory:
    echo        %RUNTIME_DIR%
    rmdir /s /q "%RUNTIME_DIR%"
    if exist "%RUNTIME_DIR%" (
        echo [ERROR] Failed to remove runtime directory.
        echo [ERROR] Another process may still be using files in this folder.
        echo [ERROR] Please close any running app started from the packaged runtime.
        pause
        exit /b 1
    )
    echo [OK] Runtime directory removed.
    echo.
)

if exist "%TEMP_INPUT_DIR%" (
    echo [INFO] Removing old temp input directory:
    echo        %TEMP_INPUT_DIR%
    rmdir /s /q "%TEMP_INPUT_DIR%"
    if exist "%TEMP_INPUT_DIR%" (
        echo [ERROR] Failed to remove temp input directory.
        echo [ERROR] Another process may still be using files in this folder.
        pause
        exit /b 1
    )
    echo [OK] Temp input directory removed.
    echo.
)

REM =========================================================
REM Recreate required directories
REM =========================================================

echo [STEP] Creating fresh output directory...
mkdir "%OUTPUT_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to create output directory:
    echo         %OUTPUT_DIR%
    pause
    exit /b 1
)
echo [OK] Output directory ready.
echo.

echo [STEP] Creating fresh temp input directory...
mkdir "%TEMP_INPUT_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to create temp input directory:
    echo         %TEMP_INPUT_DIR%
    pause
    exit /b 1
)
echo [OK] Temp input directory ready.
echo.

REM =========================================================
REM Build runtime image
REM =========================================================

echo [STEP] Building custom runtime image...
echo.
echo [COMMAND]
echo "%JLINK_CMD%" --add-modules %MODULES% --output "%RUNTIME_DIR%" --strip-debug --compress 2 --no-header-files --no-man-pages
echo.

"%JLINK_CMD%" ^
  --add-modules %MODULES% ^
  --output "%RUNTIME_DIR%" ^
  --strip-debug ^
  --compress 2 ^
  --no-header-files ^
  --no-man-pages

if errorlevel 1 (
    echo [ERROR] jlink failed to build the runtime image.
    pause
    exit /b 1
)

if not exist "%RUNTIME_DIR%\bin\java.exe" (
    echo [ERROR] Runtime image was created, but java.exe is missing:
    echo         %RUNTIME_DIR%\bin\java.exe
    pause
    exit /b 1
)

echo [OK] Runtime image created successfully.
echo.

REM =========================================================
REM Copy main jar
REM =========================================================

echo [STEP] Copying main JAR...
copy /y "%JAR_FILE%" "%TEMP_INPUT_DIR%\%MAIN_JAR_NAME%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy main JAR into temporary input directory.
    pause
    exit /b 1
)
echo [OK] Main JAR copied successfully.
echo.

REM =========================================================
REM Optional lib copy
REM =========================================================

for %%I in ("%JAR_FILE%") do set "JAR_PARENT=%%~dpI"

if exist "!JAR_PARENT!lib" (
    echo [STEP] Detected lib directory. Copying additional libraries...
    xcopy "!JAR_PARENT!lib" "%TEMP_INPUT_DIR%\lib\" /E /I /Y >nul
    if errorlevel 1 (
        echo [ERROR] Failed to copy lib directory.
        pause
        exit /b 1
    )
    echo [OK] Additional libraries copied.
    echo.
)

echo [INFO] Temporary input contents:
dir "%TEMP_INPUT_DIR%"
echo.

REM =========================================================
REM Run jpackage
REM =========================================================

echo [STEP] Running jpackage...
echo.
echo [COMMAND]
echo "%JPACKAGE_CMD%" --type exe --name "%APP_NAME%" --app-version "%APP_VERSION%" --vendor "%VENDOR%" --description "%DESCRIPTION%" --input "%TEMP_INPUT_DIR%" --main-jar "%MAIN_JAR_NAME%" --main-class "%MAIN_CLASS%" --runtime-image "%RUNTIME_DIR%" --dest "%OUTPUT_DIR%" --java-options "%JAVA_OPT_1%" --java-options "%JAVA_OPT_2%" --java-options "%JAVA_OPT_3%" --java-options "%JAVA_OPT_4%" --win-console --win-dir-chooser --win-menu --win-shortcut --verbose
echo.

"%JPACKAGE_CMD%" ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --vendor "%VENDOR%" ^
  --description "%DESCRIPTION%" ^
  --input "%TEMP_INPUT_DIR%" ^
  --main-jar "%MAIN_JAR_NAME%" ^
  --main-class "%MAIN_CLASS%" ^
  --runtime-image "%RUNTIME_DIR%" ^
  --dest "%OUTPUT_DIR%" ^
  --java-options "%JAVA_OPT_1%" ^
  --java-options "%JAVA_OPT_2%" ^
  --java-options "%JAVA_OPT_3%" ^
  --java-options "%JAVA_OPT_4%" ^
  --win-console ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --verbose

set "JPACKAGE_EXIT=%ERRORLEVEL%"
echo.
echo [INFO] jpackage exit code: %JPACKAGE_EXIT%
echo.

REM =========================================================
REM Cleanup temp input
REM =========================================================

echo [STEP] Cleaning temporary input directory...
if exist "%TEMP_INPUT_DIR%" (
    rmdir /s /q "%TEMP_INPUT_DIR%"
    if exist "%TEMP_INPUT_DIR%" (
        echo [WARN] Temp input directory could not be fully removed:
        echo        %TEMP_INPUT_DIR%
    ) else (
        echo [OK] Temp input directory removed.
    )
) else (
    echo [OK] Temp input directory already removed.
)
echo.

if not "%JPACKAGE_EXIT%"=="0" (
    echo [ERROR] Packaging failed.
    pause
    exit /b %JPACKAGE_EXIT%
)

echo =========================================================
echo Packaging completed successfully
echo =========================================================
echo Output directory:
echo %OUTPUT_DIR%
echo.
dir "%OUTPUT_DIR%"
echo.
pause