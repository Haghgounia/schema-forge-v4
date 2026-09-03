@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0\..\.."

set "PRODUCT_VERSION=4.0.0"
set "GA_JAR=schema-forge-v4-%PRODUCT_VERSION%.jar"
set "SOURCE_JAR=target\%GA_JAR%"
set "GA_CHECKSUM_FILE=distribution\checksums\SHA256SUMS.txt"
set "STAGE_PARENT=target\distribution-stage"
set "PACKAGE_ROOT=%STAGE_PARENT%\schemaforge-v4-%PRODUCT_VERSION%"
set "OUTPUT_ZIP=target\schemaforge-v4-%PRODUCT_VERSION%-distribution.zip"
set "OUTPUT_ZIP_SHA=%OUTPUT_ZIP%.sha256"

if not exist "%SOURCE_JAR%" (
  echo ERROR: Missing %SOURCE_JAR%
  echo Run distribution\scripts\reproducible-ga-build-windows.cmd first.
  exit /b 30
)

if not exist "%GA_CHECKSUM_FILE%" (
  echo ERROR: Missing %GA_CHECKSUM_FILE%
  exit /b 31
)

set "EXPECTED_GA_SHA256="
set "EXPECTED_GA_PATH="
for /f "tokens=1,2" %%A in (%GA_CHECKSUM_FILE%) do (
  set "EXPECTED_GA_SHA256=%%A"
  set "EXPECTED_GA_PATH=%%B"
)
if not defined EXPECTED_GA_SHA256 (
  echo ERROR: GA checksum file is empty or invalid.
  exit /b 32
)
if /I not "%EXPECTED_GA_PATH%"=="bin/%GA_JAR%" (
  echo ERROR: GA checksum path does not match bin/%GA_JAR%.
  exit /b 32
)
if "%EXPECTED_GA_SHA256:~63,1%"=="" (
  echo ERROR: Frozen GA SHA-256 is not 64 hexadecimal characters.
  exit /b 32
)
if not "%EXPECTED_GA_SHA256:~64,1%"=="" (
  echo ERROR: Frozen GA SHA-256 is not 64 hexadecimal characters.
  exit /b 32
)

where certutil.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: certutil.exe is required.
  exit /b 31
)

set "ACTUAL_GA_SHA256="
for /f "tokens=*" %%H in ('certutil -hashfile "%SOURCE_JAR%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "ACTUAL_GA_SHA256=%%H"
if not defined ACTUAL_GA_SHA256 (
  echo ERROR: Could not calculate SHA-256 for %SOURCE_JAR%.
  exit /b 32
)

if /I not "%ACTUAL_GA_SHA256%"=="%EXPECTED_GA_SHA256%" (
  echo ERROR: GA binary checksum mismatch.
  echo Expected: %EXPECTED_GA_SHA256%
  echo Actual  : %ACTUAL_GA_SHA256%
  echo Run distribution\scripts\reproducible-ga-build-windows.cmd before assembly.
  exit /b 33
)

if exist "%STAGE_PARENT%" rmdir /s /q "%STAGE_PARENT%"
if exist "%OUTPUT_ZIP%" del /q "%OUTPUT_ZIP%"
if exist "%OUTPUT_ZIP_SHA%" del /q "%OUTPUT_ZIP_SHA%"

mkdir "%PACKAGE_ROOT%\bin" || exit /b 34
mkdir "%PACKAGE_ROOT%\checksums" || exit /b 34

copy /y "%SOURCE_JAR%" "%PACKAGE_ROOT%\bin\%GA_JAR%" >nul || exit /b 35

xcopy /e /i /q /y "distribution\config" "%PACKAGE_ROOT%\config" >nul || exit /b 36
xcopy /e /i /q /y "distribution\docs" "%PACKAGE_ROOT%\docs" >nul || exit /b 36
xcopy /e /i /q /y "distribution\samples" "%PACKAGE_ROOT%\samples" >nul || exit /b 36

mkdir "%PACKAGE_ROOT%\scripts" >nul 2>&1
for %%F in (start-windows.cmd smoke-test-windows.cmd verify-checksum-windows.cmd) do (
  copy /y "distribution\scripts\%%F" "%PACKAGE_ROOT%\scripts\%%F" >nul || exit /b 37
)
copy /y "distribution\README.md" "%PACKAGE_ROOT%\README.md" >nul || exit /b 37
copy /y "%GA_CHECKSUM_FILE%" "%PACKAGE_ROOT%\checksums\SHA256SUMS.txt" >nul || exit /b 37

where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: powershell.exe is required to create the distribution ZIP.
  exit /b 38
)

powershell.exe -NoLogo -NoProfile -NonInteractive -Command ^
  "$ErrorActionPreference='Stop'; Compress-Archive -Path '%PACKAGE_ROOT%' -DestinationPath '%OUTPUT_ZIP%' -CompressionLevel Optimal"
if errorlevel 1 exit /b 39

set "ZIP_SHA256="
for /f "tokens=*" %%H in ('certutil -hashfile "%OUTPUT_ZIP%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "ZIP_SHA256=%%H"
if not defined ZIP_SHA256 (
  echo ERROR: Could not calculate distribution ZIP SHA-256.
  exit /b 40
)

> "%OUTPUT_ZIP_SHA%" echo %ZIP_SHA256%  schemaforge-v4-%PRODUCT_VERSION%-distribution.zip

echo.
echo PASS: SchemaForge V4 %PRODUCT_VERSION% distribution assembled.
echo JAR SHA-256 : %ACTUAL_GA_SHA256%
echo ZIP          : %OUTPUT_ZIP%
echo ZIP SHA-256 : %ZIP_SHA256%
echo Checksum file: %OUTPUT_ZIP_SHA%
exit /b 0
