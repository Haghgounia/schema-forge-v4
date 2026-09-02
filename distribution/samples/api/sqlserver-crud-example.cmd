@echo off
setlocal
if "%SCHEMAFORGE_BASE_URL%"=="" set "SCHEMAFORGE_BASE_URL=http://localhost:9090"
set "SCHEMA_NAME=TSTSHMA"
set "TABLE_NAME=CUSTOMERS"
curl.exe --fail-with-body -sS -X POST "%SCHEMAFORGE_BASE_URL%/api/v1/generate/sqlserver/crud" ^
  -H "Content-Type: application/json" ^
  --data "{\"schema\":\"%SCHEMA_NAME%\",\"table\":\"%TABLE_NAME%\"}" ^
  -o sqlserver-crud.sql
if errorlevel 1 exit /b %ERRORLEVEL%
echo PASS: sqlserver-crud.sql
