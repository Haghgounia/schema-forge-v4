@echo off
setlocal
pushd "%~dp0.."

set "DEFAULT_INPUT=D:\get-git-doc-files-master\SchemaForgeCanonicalJson"
set "INPUT=%~1"
if "%INPUT%"=="" set "INPUT=%DEFAULT_INPUT%"

set "OUTPUT_DIR=%~2"
if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=%CD%\target\mermaid-runtime-test"

if not exist "%INPUT%" (
  echo [ERROR] Input does not exist:
  echo         %INPUT%
  echo.
  echo Usage:
  echo   scripts\TEST-MERMAID-API.cmd [snapshot-or-directory] [output-directory]
  popd
  exit /b 2
)

set "SNAPSHOT="
if exist "%INPUT%\." goto input_is_directory
set "SNAPSHOT=%INPUT%"
goto snapshot_found

:input_is_directory
for /r "%INPUT%" %%F in (*.schema.json) do (
  set "SNAPSHOT=%%~fF"
  goto snapshot_found
)

echo [ERROR] No *.schema.json file was found under:
echo         %INPUT%
popd
exit /b 2

:snapshot_found
if "%SNAPSHOT%"=="" (
  echo [ERROR] Could not resolve a canonical snapshot.
  popd
  exit /b 2
)

where curl.exe >nul 2>nul
if errorlevel 1 (
  echo [ERROR] curl.exe was not found in PATH.
  popd
  exit /b 2
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if errorlevel 1 (
  echo [ERROR] Could not create output directory:
  echo         %OUTPUT_DIR%
  popd
  exit /b 2
)

set "HEADERS=%OUTPUT_DIR%\mermaid-headers.txt"
set "OUTPUT=%OUTPUT_DIR%\schemaforge-single-er.mmd"

rem First verify that the SchemaForge API is already running.
curl.exe --silent --fail "http://localhost:9090/v3/api-docs" >nul
if errorlevel 1 (
  echo [ERROR] SchemaForge API is not reachable on http://localhost:9090
  echo.
  echo Start it first in another Command Prompt:
  echo   scripts\RUN-MERMAID-API.cmd
  echo.
  popd
  exit /b 3
)

echo ============================================================
echo SchemaForge V4 - Mermaid Runtime Smoke Test
echo ============================================================
echo Input  : %SNAPSHOT%
echo Output : %OUTPUT%
echo.

curl.exe --fail-with-body --show-error --silent ^
  -D "%HEADERS%" ^
  -X POST "http://localhost:9090/api/v1/diagram/mermaid/canonical-json?type=er&scope=all&includeColumns=true&includeDataTypes=true" ^
  -F "file=@%SNAPSHOT%" ^
  -o "%OUTPUT%"
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [ERROR] Mermaid endpoint returned an error. curl exit code: %RC%
  if exist "%OUTPUT%" (
    echo ---------------- response body ----------------
    type "%OUTPUT%"
    echo.
    echo -------------------------------------------------
  )
  popd
  exit /b %RC%
)

findstr /b /c:"erDiagram" "%OUTPUT%" >nul
if errorlevel 1 (
  echo [ERROR] HTTP call succeeded, but output does not start with erDiagram.
  echo         Inspect: %OUTPUT%
  popd
  exit /b 4
)

echo [PASS] Mermaid API returned a valid ER artifact.
echo.
echo Headers:
type "%HEADERS%"
echo.
echo Mermaid file:
echo   %OUTPUT%
echo ============================================================

popd
exit /b 0
