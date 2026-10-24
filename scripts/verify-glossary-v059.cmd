@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
if errorlevel 1 exit /b 1
if not defined SCHEMAFORGE_GLOSSARY_DATABASE_PATH set "SCHEMAFORGE_GLOSSARY_DATABASE_PATH=%CD%\data\schemaforge-glossary"
if not exist "%SCHEMAFORGE_GLOSSARY_DATABASE_PATH%.mv.db" (
  echo [ERROR] H2 file is missing: %SCHEMAFORGE_GLOSSARY_DATABASE_PATH%.mv.db
  echo [ACTION] Check SCHEMAFORGE_GLOSSARY_DATABASE_PATH and restart the new Backend once.
  exit /b 2
)
if not defined H2_JAR set "H2_JAR=%USERPROFILE%\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar"
if not exist "%H2_JAR%" (
  echo [ERROR] H2 jar missing. Run mvnw.cmd dependency:resolve.
  exit /b 3
)
set "DB_URL=jdbc:h2:file:%SCHEMAFORGE_GLOSSARY_DATABASE_PATH:\=/%;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE"
echo [INFO] Verify official baseline statuses, user changes and H2 migration. Backend must be stopped.
java -cp "%H2_JAR%" org.h2.tools.Shell -url "%DB_URL%" -user sa -password "" -sql "SELECT KIND, STATUS, COUNT(*) AS TERMS FROM GLOSSARY_TERM WHERE SOURCE='ECOBANK_ATOMIC_XLSX' AND IS_DELETED=FALSE GROUP BY KIND, STATUS ORDER BY KIND, STATUS"
if errorlevel 1 exit /b 1
set "MARKER_LOG=%TEMP%\schemaforge-v059-glossary-marker-%RANDOM%.txt"
java -cp "%H2_JAR%" org.h2.tools.Shell -url "%DB_URL%" -user sa -password "" -sql "SELECT MIGRATION_ID, SOURCE_ENTRIES, PROMOTED_COUNT, SKIPPED_COUNT, APPLIED_AT FROM GLOSSARY_BASELINE_MIGRATION" > "%MARKER_LOG%"
if errorlevel 1 (
  type "%MARKER_LOG%"
  del /q "%MARKER_LOG%" >nul 2>&1
  exit /b 1
)
type "%MARKER_LOG%"
findstr /C:"ECOBANK_01_ATOMIC_TERMS_APPROVED_V059" "%MARKER_LOG%" >nul
if errorlevel 1 (
  echo [FAIL] V0.5.9 approval migration marker is not present.
  del /q "%MARKER_LOG%" >nul 2>&1
  exit /b 4
)
del /q "%MARKER_LOG%" >nul 2>&1
echo [OK] H2 V0.5.9 migration marker exists. Review per-kind APPROVED counts above.
exit /b 0
