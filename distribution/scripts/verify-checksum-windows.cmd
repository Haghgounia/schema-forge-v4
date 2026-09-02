@echo off
setlocal
cd /d "%~dp0\.."

set "SCHEMAFORGE_JAR=bin\schema-forge-v4-4.0.0.jar"
set "CHECKSUM_FILE=checksums\SHA256SUMS.txt"

if not exist "%SCHEMAFORGE_JAR%" (
  echo ERROR: Missing %SCHEMAFORGE_JAR%
  exit /b 30
)

if not exist "%CHECKSUM_FILE%" (
  echo ERROR: Missing %CHECKSUM_FILE%
  exit /b 31
)

for /f "tokens=1" %%H in (%CHECKSUM_FILE%) do set "EXPECTED_SHA256=%%H"
for /f %%H in ('powershell.exe -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%SCHEMAFORGE_JAR%').Hash.ToLowerInvariant()"') do set "ACTUAL_SHA256=%%H"

if /I not "%EXPECTED_SHA256%"=="%ACTUAL_SHA256%" (
  echo FAIL: SchemaForge GA binary checksum mismatch.
  echo Expected: %EXPECTED_SHA256%
  echo Actual:   %ACTUAL_SHA256%
  exit /b 32
)

echo PASS: SchemaForge GA binary checksum verified.
echo SHA-256: %ACTUAL_SHA256%
exit /b 0
