@echo off
cd /d D:\code\ai_project\javaclawbot
call maven-compiled.bat > compile-output.txt 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> compile-output.txt
