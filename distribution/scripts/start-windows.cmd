@echo off
setlocal
cd /d "%~dp0\.."

set "SCHEMAFORGE_JAR=bin\schema-forge-v4-4.0.0.jar"
set "SCHEMAFORGE_CONFIG=config\application.yml"

where java >nul 2>&1
if errorlevel 1 (
  echo ERROR: Java is not available on PATH. SchemaForge V4 requires Java 21.
  exit /b 10
)

if not exist "%SCHEMAFORGE_JAR%" (
  echo ERROR: Missing %SCHEMAFORGE_JAR%
  exit /b 11
)

if not exist "%SCHEMAFORGE_CONFIG%" (
  echo ERROR: Missing %SCHEMAFORGE_CONFIG%
  echo Restore config\application.yml from config\application-example.yml.
  exit /b 12
)

echo Starting SchemaForge V4 4.0.0...
echo Runtime configuration: %SCHEMAFORGE_CONFIG%
java -jar "%SCHEMAFORGE_JAR%" --spring.config.location=file:./config/application.yml
exit /b %ERRORLEVEL%
