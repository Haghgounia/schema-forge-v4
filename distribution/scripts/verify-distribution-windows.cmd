@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0\..\.."

set "PRODUCT_VERSION=4.0.0"
set "GA_JAR=schema-forge-v4-%PRODUCT_VERSION%.jar"
set "PACKAGE_ROOT=target\distribution-stage\schemaforge-v4-%PRODUCT_VERSION%"
set "PACKAGE_JAR=%PACKAGE_ROOT%\bin\%GA_JAR%"
set "PACKAGE_CHECKSUM=%PACKAGE_ROOT%\checksums\SHA256SUMS.txt"
set "OUTPUT_ZIP=target\schemaforge-v4-%PRODUCT_VERSION%-distribution.zip"
set "OUTPUT_ZIP_SHA=%OUTPUT_ZIP%.sha256"

for %%F in (
  "%PACKAGE_JAR%"
  "%PACKAGE_ROOT%\config\application.yml"
  "%PACKAGE_ROOT%\scripts\start-windows.cmd"
  "%PACKAGE_ROOT%\scripts\smoke-test-windows.cmd"
  "%PACKAGE_ROOT%\docs\INSTALLATION.md"
  "%PACKAGE_ROOT%\docs\CONFIGURATION.md"
  "%PACKAGE_ROOT%\docs\API-GUIDE.md"
  "%PACKAGE_ROOT%\docs\DBA-GUIDE.md"
  "%PACKAGE_ROOT%\docs\OPERATIONS-GUIDE.md"
  "%PACKAGE_CHECKSUM%"
  "%OUTPUT_ZIP%"
  "%OUTPUT_ZIP_SHA%"
) do (
  if not exist %%F (
    echo FAIL: Missing %%~F
    exit /b 50
  )
)

set "EXPECTED_GA_SHA256="
for /f "tokens=1" %%H in (%PACKAGE_CHECKSUM%) do set "EXPECTED_GA_SHA256=%%H"
set "ACTUAL_GA_SHA256="
for /f "tokens=*" %%H in ('certutil -hashfile "%PACKAGE_JAR%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "ACTUAL_GA_SHA256=%%H"
if /I not "%ACTUAL_GA_SHA256%"=="%EXPECTED_GA_SHA256%" (
  echo FAIL: Staged GA JAR SHA-256 mismatch.
  echo Expected: %EXPECTED_GA_SHA256%
  echo Actual  : %ACTUAL_GA_SHA256%
  exit /b 51
)

set "EXPECTED_ZIP_SHA256="
for /f "tokens=1" %%H in (%OUTPUT_ZIP_SHA%) do set "EXPECTED_ZIP_SHA256=%%H"
set "ACTUAL_ZIP_SHA256="
for /f "tokens=*" %%H in ('certutil -hashfile "%OUTPUT_ZIP%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "ACTUAL_ZIP_SHA256=%%H"
if /I not "%ACTUAL_ZIP_SHA256%"=="%EXPECTED_ZIP_SHA256%" (
  echo FAIL: Distribution ZIP SHA-256 mismatch.
  echo Expected: %EXPECTED_ZIP_SHA256%
  echo Actual  : %ACTUAL_ZIP_SHA256%
  exit /b 52
)

echo PASS: Distribution stage, GA binary and ZIP checksums verified.
echo GA JAR SHA-256 : %ACTUAL_GA_SHA256%
echo ZIP SHA-256    : %ACTUAL_ZIP_SHA256%
exit /b 0
