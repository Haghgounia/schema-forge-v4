@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem ============================================================================
rem Resume SchemaForge Detached LegacyUniqueKeyRecoveryProbeIT
rem Starts from step 4 using an already prepared detached bundle.
rem ============================================================================

if "%~1"=="" goto :USAGE
if "%~2"=="" goto :USAGE

set "BUNDLE=%~1"
set "WORD_ROOT=%~2"
set "LIB_DIR=%BUNDLE%\lib"
set "CLASSES_DIR=%BUNDLE%\classes"
set "REPORT_ROOT=%BUNDLE%\reports"
set "SRC_DIR=%BUNDLE%\standalone-src\com\behsazan\schemaforge\integration"
set "STANDALONE_SRC=%SRC_DIR%\LegacyUkProbeStandaloneMain.java"

for %%I in ("%BUNDLE%") do set "STAMP=%%~nxI"
set "RUNNER_JAR=%BUNDLE%\schema-forge-v4-legacy-uk-probe-%STAMP%.jar"

if not exist "%BUNDLE%\" (
  echo [ERROR] Bundle not found:
  echo         %BUNDLE%
  exit /b 2
)

if not exist "%LIB_DIR%\" (
  echo [ERROR] Dependency directory not found:
  echo         %LIB_DIR%
  exit /b 3
)

if not exist "%CLASSES_DIR%\" (
  echo [ERROR] Frozen classes directory not found:
  echo         %CLASSES_DIR%
  echo.
  echo The original detached runner did not finish step 3.
  exit /b 4
)

if not exist "%WORD_ROOT%\" (
  echo [ERROR] WORD_ROOT not found:
  echo         %WORD_ROOT%
  exit /b 5
)

mkdir "%SRC_DIR%" >nul 2>&1
mkdir "%REPORT_ROOT%" >nul 2>&1

echo.
echo ============================================================
echo Resume detached Legacy UK probe
echo ============================================================
echo Bundle    : %BUNDLE%
echo Word root : %WORD_ROOT%
echo Reports   : %REPORT_ROOT%
echo.

rem IMPORTANT:
rem Do not generate this file inside a parenthesized batch block.
rem Parentheses in Java method declarations can otherwise be parsed by cmd.exe.

> "%STANDALONE_SRC%" echo package com.behsazan.schemaforge.integration;
>> "%STANDALONE_SRC%" echo.
>> "%STANDALONE_SRC%" echo public final class LegacyUkProbeStandaloneMain {
>> "%STANDALONE_SRC%" echo     private LegacyUkProbeStandaloneMain^(^) {}
>> "%STANDALONE_SRC%" echo.
>> "%STANDALONE_SRC%" echo     public static void main^(String[] args^) throws Exception {
>> "%STANDALONE_SRC%" echo         new LegacyUniqueKeyRecoveryProbeIT^(^)
>> "%STANDALONE_SRC%" echo                 .probesRecoveredUniqueKeysWithoutMutatingCanonicalSnapshots^(^);
>> "%STANDALONE_SRC%" echo     }
>> "%STANDALONE_SRC%" echo }

echo [4/5] Compiling standalone probe entry point...

javac -encoding UTF-8 ^
  -cp "%CLASSES_DIR%;%LIB_DIR%\*" ^
  -d "%CLASSES_DIR%" ^
  "%STANDALONE_SRC%"

if errorlevel 1 (
  echo.
  echo [ERROR] Standalone probe entry-point compilation failed.
  exit /b 10
)

echo.
echo Creating frozen runner JAR...

jar --create --file "%RUNNER_JAR%" -C "%CLASSES_DIR%" .
if errorlevel 1 (
  echo [ERROR] Probe JAR creation failed.
  exit /b 11
)

echo.
echo Frozen JAR created:
echo   %RUNNER_JAR%

rem No longer needed after JAR creation.
rmdir /S /Q "%BUNDLE%\standalone-src" >nul 2>&1

echo.
echo [5/5] Running frozen probe...
echo ============================================================
echo Legacy schema : TSTSHMA
echo Expected docs : 5321
echo Fail on errors: false
echo Threads       : 8
echo Progress every: 100
echo ============================================================
echo.

pushd "%BUNDLE%"

java ^
  -Dfile.encoding=UTF-8 ^
  -Dschemaforge.uk.probe.wordRoot="%WORD_ROOT%" ^
  -Dschemaforge.uk.probe.legacySchema=TSTSHMA ^
  -Dschemaforge.uk.probe.expectedMinDocuments=5321 ^
  -Dschemaforge.uk.probe.failOnErrors=false ^
  -Dschemaforge.uk.probe.threads=8 ^
  -Dschemaforge.uk.probe.progressEveryDocuments=100 ^
  -Dschemaforge.uk.probe.outputDir="%REPORT_ROOT%" ^
  -cp "%RUNNER_JAR%;%LIB_DIR%\*" ^
  com.behsazan.schemaforge.integration.LegacyUkProbeStandaloneMain

set "PROBE_RC=%ERRORLEVEL%"
popd

echo.
echo ============================================================
if "%PROBE_RC%"=="0" goto :SUCCESS

echo PROBE FAILED - exit code %PROBE_RC%
echo Runner JAR:
echo   %RUNNER_JAR%
echo Reports:
echo   %REPORT_ROOT%
echo ============================================================
exit /b %PROBE_RC%

:SUCCESS
echo PROBE COMPLETED SUCCESSFULLY
echo Runner JAR:
echo   %RUNNER_JAR%
echo Reports:
echo   %REPORT_ROOT%
echo ============================================================
exit /b 0

:USAGE
echo.
echo Usage:
echo   %~nx0 "BUNDLE_PATH" "WORD_ROOT"
echo.
echo For your current interrupted run:
echo   %~nx0 "D:\SchemaForge-Runners\LegacyUniqueKeyRecoveryProbe\20260829_151249" "D:\get-git-doc-files-master"
echo.
exit /b 1
