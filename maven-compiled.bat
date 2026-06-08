@echo off
set "LOGFILE=D:\code\ai_project\javaclawbot\maven-compiled.log"
cd /d D:\code\ai_project\javaclawbot

echo ================================================================
echo [%date% %time%] Maven compile started...
echo ================================================================
echo [%date% %time%] Maven compile started... > "%LOGFILE%"

:: Updated 2026-06-08: switched from IDEA 2024.03.07 bundled Maven to standalone Maven 3.8.1
"C:\Program Files\Java\jdk-17\bin\java.exe" -Dmaven.multiModuleProjectDirectory=D:\code\ai_project\javaclawbot -Djansi.passthrough=true -Dmaven.home=D:\apps\maven\maven-3.8.1 -Dclassworlds.conf=D:\apps\maven\maven-3.8.1\bin\m2.conf -Dfile.encoding=UTF-8 -classpath D:\apps\maven\maven-3.8.1\boot\plexus-classworlds-2.6.0.jar;D:\apps\maven\maven-3.8.1\boot\plexus-classworlds.license org.codehaus.classworlds.Launcher -Dmaven.repo.local=D:\apps\maven\repository package

set EXITCODE=%ERRORLEVEL%

echo ================================================================
echo [%date% %time%] Exit code: %EXITCODE%
echo ================================================================
echo [%date% %time%] Exit code: %EXITCODE% >> "%LOGFILE%"

exit /b %EXITCODE%