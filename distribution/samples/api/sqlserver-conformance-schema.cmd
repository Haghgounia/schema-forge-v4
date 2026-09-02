@echo off
setlocal
if "%SCHEMAFORGE_BASE_URL%"=="" set "SCHEMAFORGE_BASE_URL=http://localhost:9090"
set "SCHEMA_NAME=TSTSHMA"
curl.exe --fail-with-body -sS "%SCHEMAFORGE_BASE_URL%/api/v1/conformance/schema?platform=sqlserver&schema=%SCHEMA_NAME%" -o sqlserver-conformance.json
if errorlevel 1 exit /b %ERRORLEVEL%
findstr /C:"schemaforge-schema-conformance/v3" sqlserver-conformance.json >nul
if errorlevel 1 exit /b 2
echo PASS: sqlserver-conformance.json
