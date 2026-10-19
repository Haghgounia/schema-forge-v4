@echo off
setlocal EnableExtensions
rem Initialize local SchemaForge Glossary H2 DB directly from the Atomic-only SQL seed.
rem Run from any Windows CMD location. Requires patched H2 2.4.240 (issue #4247).
rem Back up any existing data/schemaforge-glossary.mv.db before running.
for %%I in ("%~dp0..\..") do set "SCHEMAFORGE_PROJECT_ROOT=%%~fI"
set "SCHEMAFORGE_GLOSSARY_SQL=%SCHEMAFORGE_PROJECT_ROOT%\distribution\glossary\bootstrap-atomic-h2.sql"
if not exist "%SCHEMAFORGE_GLOSSARY_SQL%" (
  echo [ERROR] Atomic glossary SQL seed file not found.
  exit /b 2
)
if not defined H2_JAR (
  set "H2_JAR=%USERPROFILE%\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar"
)
if not exist "%H2_JAR%" (
  echo [ERROR] H2_JAR path not found: %H2_JAR%
  echo [ACTION] Run: mvnw.cmd dependency:resolve
  echo [ACTION] Or set H2_JAR to the exact H2 JDBC jar path and rerun.
  exit /b 3
)
rem Refuse a known vulnerable H2 jar if the environment variable overrides our default.
for %%I in ("%H2_JAR%") do set "SCHEMAFORGE_H2_JAR_NAME=%%~nxI"
if /i "%SCHEMAFORGE_H2_JAR_NAME%"=="h2-2.3.232.jar" (
  echo [ERROR] H2 2.3.232 is affected by database compaction data loss ^(#4247^).
  echo [ACTION] Clear H2_JAR and rerun after mvnw.cmd dependency:resolve.
  exit /b 4
)
if not exist "%SCHEMAFORGE_PROJECT_ROOT%\data" mkdir "%SCHEMAFORGE_PROJECT_ROOT%\data"
set "SCHEMAFORGE_H2_URL=jdbc:h2:file:%SCHEMAFORGE_PROJECT_ROOT:\=/%/data/schemaforge-glossary;DB_CLOSE_ON_EXIT=FALSE"
echo [INFO] H2 jar: %H2_JAR%
echo [INFO] H2 base: %SCHEMAFORGE_PROJECT_ROOT%\data\schemaforge-glossary
rem H2 RunScript 2.4.240 reads script files as UTF-8 by default.
rem Its command line does NOT accept -charset. Keep this invocation restricted to supported options.
java -cp "%H2_JAR%" org.h2.tools.RunScript -url "%SCHEMAFORGE_H2_URL%" -user sa -password "" -script "%SCHEMAFORGE_GLOSSARY_SQL%"
if errorlevel 1 (
  echo [ERROR] Atomic glossary import failed.
  exit /b 1
)
java -cp "%H2_JAR%" org.h2.tools.Shell -url "%SCHEMAFORGE_H2_URL%" -user sa -password "" -sql "SELECT KIND, STATUS, COUNT(*) AS TERMS FROM GLOSSARY_TERM WHERE SOURCE='ECOBANK_ATOMIC_XLSX' AND IS_DELETED=FALSE GROUP BY KIND, STATUS ORDER BY KIND, STATUS"
if errorlevel 1 exit /b 1
echo [OK] Database created or updated. Expected first run: 800 ATOMIC plus 4 PHRASE, all CANDIDATE.
exit /b 0
