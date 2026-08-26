@echo off
setlocal
cd /d "%~dp0.."

set "R8_WORK_DIR=.r8.1"
if not exist "%R8_WORK_DIR%" mkdir "%R8_WORK_DIR%"
set "R8_LOG=%R8_WORK_DIR%\r8.1-full-regression.log"

echo SchemaForge V4 R8.1 full clean regression
echo Project: %CD%
echo Log    : %R8_LOG%
echo.

call mvnw.cmd clean test > "%R8_LOG%" 2>&1
set "R8_RC=%ERRORLEVEL%"

if not exist "target\test-reports" mkdir "target\test-reports"
copy /y "%R8_LOG%" "target\test-reports\r8.1-full-regression.log" >nul

type "%R8_LOG%"
echo.
if "%R8_RC%"=="0" (
  echo R8.1 FULL REGRESSION: PASS
) else (
  echo R8.1 FULL REGRESSION: FAIL ^(exit %R8_RC%^)
)

exit /b %R8_RC%
