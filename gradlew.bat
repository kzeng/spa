@echo off
rem Gradle wrapper script for Windows

set DIR=%~dp0
if "%DIR%"=="" set DIR=.

set GRADLE_HOME=%DIR%gradle\wrapper
set GRADLE_VERSION=7.5.1

if not exist "%GRADLE_HOME%\gradle-wrapper.jar" (
    echo "Gradle wrapper jar not found. Please run 'gradle wrapper' to generate it."
    exit /b 1
)

java -jar "%GRADLE_HOME%\gradle-wrapper.jar" %*