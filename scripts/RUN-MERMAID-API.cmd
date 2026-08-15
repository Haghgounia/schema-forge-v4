@echo off
setlocal
pushd "%~dp0.."

if not exist "mvnw.cmd" (
  echo [ERROR] mvnw.cmd was not found in %CD%
  popd
  exit /b 2
)

where java.exe >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found in PATH.
  echo         SchemaForge V4 requires Java 21.
  popd
  exit /b 2
)

rem Keep Mermaid runtime smoke testing independent from live metadata databases.
set "SCHEMAFORGE_METADATA_ORACLE_ENABLED=false"
set "SCHEMAFORGE_METADATA_POSTGRESQL_ENABLED=false"
set "SCHEMAFORGE_METADATA_DB2ZOS_ENABLED=false"
set "SCHEMAFORGE_METADATA_SQLSERVER_ENABLED=false"

echo ============================================================
echo SchemaForge V4 - Mermaid API
echo ============================================================
echo Project : %CD%
echo Port    : 9090
echo Swagger : http://localhost:9090/swagger-ui.html
echo API     : http://localhost:9090/api/v1/diagram/mermaid/canonical-json
echo.
echo Leave this window open while running scripts\TEST-MERMAID-API.cmd.
echo ============================================================
echo.

call mvnw.cmd spring-boot:run
set "RC=%ERRORLEVEL%"

popd
exit /b %RC%
