@echo off
setlocal
if "%SCHEMAFORGE_BASE_URL%"=="" set "SCHEMAFORGE_BASE_URL=http://localhost:9090"
curl.exe --fail-with-body -sS "%SCHEMAFORGE_BASE_URL%/v3/api-docs" -o schemaforge-api-docs.json
if errorlevel 1 exit /b %ERRORLEVEL%
echo PASS: schemaforge-api-docs.json
