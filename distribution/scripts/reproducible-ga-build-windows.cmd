@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0\..\.."

set "PRODUCT_VERSION=4.0.0"
set "GA_JAR=schema-forge-v4-%PRODUCT_VERSION%.jar"
set "SOURCE_JAR=target\%GA_JAR%"
set "CHECKSUM_FILE=distribution\checksums\SHA256SUMS.txt"

where certutil.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: certutil.exe is required.
  exit /b 60
)

if not exist "mvnw.cmd" (
  echo ERROR: mvnw.cmd was not found at the project root.
  exit /b 61
)

echo [1/2] Building reproducible GA binary...
call mvnw.cmd clean package -DskipTests
if errorlevel 1 exit /b 62

set "HASH1="
for /f "tokens=*" %%H in ('certutil -hashfile "%SOURCE_JAR%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "HASH1=%%H"
if not defined HASH1 (
  echo ERROR: Could not calculate first GA JAR SHA-256.
  exit /b 63
)

echo First SHA-256 : !HASH1!

echo [2/2] Rebuilding from clean state...
call mvnw.cmd clean package -DskipTests
if errorlevel 1 exit /b 64

set "HASH2="
for /f "tokens=*" %%H in ('certutil -hashfile "%SOURCE_JAR%" SHA256 ^| findstr /R /I "^[0-9a-f][0-9a-f]*$"') do set "HASH2=%%H"
if not defined HASH2 (
  echo ERROR: Could not calculate second GA JAR SHA-256.
  exit /b 65
)

echo Second SHA-256: !HASH2!

if /I not "!HASH1!"=="!HASH2!" (
  echo FAIL: GA build is still not byte-for-byte reproducible.
  echo First : !HASH1!
  echo Second: !HASH2!
  exit /b 66
)

> "%CHECKSUM_FILE%" echo !HASH2!  bin/%GA_JAR%

echo.
echo PASS: GA build is byte-for-byte reproducible.
echo Frozen GA SHA-256: !HASH2!
echo Updated: %CHECKSUM_FILE%
exit /b 0
