@echo off
setlocal
cd /d "%~dp0\.."

if "%SCHEMAFORGE_BASE_URL%"=="" set "SCHEMAFORGE_BASE_URL=http://localhost:9090"
set "SMOKE_FILE=%TEMP%\schemaforge-v4-api-docs-%RANDOM%-%RANDOM%.json"

where curl.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: curl.exe is required for the runtime smoke test.
  exit /b 20
)

curl.exe --fail-with-body -sS "%SCHEMAFORGE_BASE_URL%/v3/api-docs" -o "%SMOKE_FILE%"
if errorlevel 1 (
  echo FAIL: %SCHEMAFORGE_BASE_URL%/v3/api-docs
  del /q "%SMOKE_FILE%" >nul 2>&1
  exit /b 21
)

findstr /C:"/api/v1/conformance/schema" "%SMOKE_FILE%" >nul
if errorlevel 1 (
  echo FAIL: Expected Schema Conformance API path was not found in OpenAPI output.
  del /q "%SMOKE_FILE%" >nul 2>&1
  exit /b 22
)

del /q "%SMOKE_FILE%" >nul 2>&1
echo PASS: SchemaForge API runtime smoke test.
exit /b 0
