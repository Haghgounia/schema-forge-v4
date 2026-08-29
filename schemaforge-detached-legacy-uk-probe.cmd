@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem ============================================================================
rem SchemaForge V4 - Detached LegacyUniqueKeyRecoveryProbeIT Runner
rem
rem Purpose:
rem   1) Freeze the current SchemaForge source into a separate snapshot.
rem   2) Build the probe from that snapshot, not from D:\Projects\schema-forge-v4.
rem   3) Package application + test classes into a renamed probe JAR.
rem   4) Copy all test/runtime dependencies into a private lib directory.
rem   5) Run the probe in a separate BELOWNORMAL-priority CMD window.
rem
rem After "[SNAPSHOT COMPLETE]" is printed, the original project is no longer
rem used by the worker. You may edit/replace/build/clean the main project.
rem ============================================================================

if /I "%~1"=="--worker" goto :WORKER

set "PROJECT_ROOT=D:\Projects\schema-forge-v4"
set "RUNNERS_ROOT=D:\SchemaForge-Runners\LegacyUniqueKeyRecoveryProbe"

if "%~1"=="" goto :USAGE
set "WORD_ROOT=%~1"

if not exist "%PROJECT_ROOT%\pom.xml" (
  echo [ERROR] SchemaForge project not found:
  echo         %PROJECT_ROOT%
  exit /b 2
)

if not exist "%WORD_ROOT%\" (
  echo [ERROR] WORD_ROOT does not exist:
  echo         %WORD_ROOT%
  exit /b 3
)

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "STAMP=%%I"

set "BUNDLE=%RUNNERS_ROOT%\%STAMP%"
set "SNAPSHOT=%BUNDLE%\source-snapshot"

mkdir "%BUNDLE%" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Cannot create:
  echo         %BUNDLE%
  exit /b 4
)

echo.
echo ============================================================
echo SchemaForge detached Legacy UK probe
echo ============================================================
echo Source project : %PROJECT_ROOT%
echo Word corpus    : %WORD_ROOT%
echo Runner bundle  : %BUNDLE%
echo.
echo [1/2] Freezing current project source...

robocopy "%PROJECT_ROOT%" "%SNAPSHOT%" /E /COPY:DAT /DCOPY:T /R:1 /W:1 /NFL /NDL /NJH /NJS /NP ^
  /XD target .git .idea .gradle ^
  /XF *.iml >nul

set "ROBOCOPY_RC=%ERRORLEVEL%"
if %ROBOCOPY_RC% GEQ 8 (
  echo [ERROR] Snapshot copy failed. Robocopy exit code: %ROBOCOPY_RC%
  exit /b %ROBOCOPY_RC%
)

copy /Y "%~f0" "%BUNDLE%\detached-legacy-uk-probe.cmd" >nul

> "%BUNDLE%\runner-info.txt" (
  echo SchemaForge detached LegacyUniqueKeyRecoveryProbeIT runner
  echo Created       : %DATE% %TIME%
  echo Source project : %PROJECT_ROOT%
  echo Word root      : %WORD_ROOT%
  echo Snapshot       : %SNAPSHOT%
  echo Expected docs  : 5321
  echo Threads        : 8
  echo Progress every : 100
)

echo [SNAPSHOT COMPLETE]
echo.
echo The worker will now build and run ONLY from:
echo   %BUNDLE%
echo.
echo You may now continue working in:
echo   %PROJECT_ROOT%
echo.
echo [2/2] Starting detached worker in a separate window...

start "SchemaForge Legacy UK Probe %STAMP%" /BELOWNORMAL cmd /k ^
  call "%BUNDLE%\detached-legacy-uk-probe.cmd" --worker "%BUNDLE%" "%WORD_ROOT%" "%STAMP%"

echo.
echo Detached worker started.
echo Bundle:
echo   %BUNDLE%
echo.
exit /b 0


:WORKER
set "BUNDLE=%~2"
set "WORD_ROOT=%~3"
set "STAMP=%~4"
set "SNAPSHOT=%BUNDLE%\source-snapshot"
set "LIB_DIR=%BUNDLE%\lib"
set "CLASSES_DIR=%BUNDLE%\classes"
set "RUNNER_JAR=%BUNDLE%\schema-forge-v4-legacy-uk-probe-%STAMP%.jar"
set "REPORT_ROOT=%BUNDLE%\reports"
set "STANDALONE_SRC=%BUNDLE%\standalone-src\com\behsazan\schemaforge\integration\LegacyUkProbeStandaloneMain.java"

title SchemaForge Legacy UK Probe - %STAMP%

echo.
echo ============================================================
echo Detached worker - build phase
echo ============================================================
echo Snapshot : %SNAPSHOT%
echo Reports  : %REPORT_ROOT%
echo.

if not exist "%SNAPSHOT%\mvnw.cmd" (
  echo [ERROR] mvnw.cmd not found in frozen snapshot.
  goto :WORKER_FAIL
)

mkdir "%LIB_DIR%" >nul 2>&1
mkdir "%CLASSES_DIR%" >nul 2>&1
mkdir "%BUNDLE%\standalone-src\com\behsazan\schemaforge\integration" >nul 2>&1
mkdir "%REPORT_ROOT%" >nul 2>&1

pushd "%SNAPSHOT%"

echo [1/5] Compiling frozen application and test classes...
call mvnw.cmd -DskipTests test-compile
if errorlevel 1 (
  popd
  echo [ERROR] test-compile failed.
  goto :WORKER_FAIL
)

echo.
echo [2/5] Copying frozen test/runtime dependencies...
call mvnw.cmd -DskipTests dependency:copy-dependencies ^
  -DincludeScope=test ^
  -DoutputDirectory="%LIB_DIR%"
if errorlevel 1 (
  popd
  echo [ERROR] dependency copy failed.
  goto :WORKER_FAIL
)

echo.
echo [3/5] Copying compiled classes into private bundle...
xcopy "target\classes\*" "%CLASSES_DIR%\" /E /I /Q /Y >nul
if errorlevel 1 (
  popd
  echo [ERROR] Could not copy target\classes.
  goto :WORKER_FAIL
)

xcopy "target\test-classes\*" "%CLASSES_DIR%\" /E /I /Q /Y >nul
if errorlevel 1 (
  popd
  echo [ERROR] Could not copy target\test-classes.
  goto :WORKER_FAIL
)

popd

echo.
echo [4/5] Building standalone probe entry point...

> "%STANDALONE_SRC%" (
  echo package com.behsazan.schemaforge.integration;
  echo.
  echo public final class LegacyUkProbeStandaloneMain {
  echo     private LegacyUkProbeStandaloneMain() {}
  echo.
  echo     public static void main(String[] args) throws Exception {
  echo         new LegacyUniqueKeyRecoveryProbeIT()
  echo                 .probesRecoveredUniqueKeysWithoutMutatingCanonicalSnapshots();
  echo     }
  echo }
)

javac -encoding UTF-8 ^
  -cp "%CLASSES_DIR%;%LIB_DIR%\*" ^
  -d "%CLASSES_DIR%" ^
  "%STANDALONE_SRC%"

if errorlevel 1 (
  echo [ERROR] Standalone probe entry-point compilation failed.
  goto :WORKER_FAIL
)

jar --create --file "%RUNNER_JAR%" -C "%CLASSES_DIR%" .
if errorlevel 1 (
  echo [ERROR] Probe JAR creation failed.
  goto :WORKER_FAIL
)

echo.
echo Frozen JAR created:
echo   %RUNNER_JAR%

rem The Java process below uses only RUNNER_JAR + BUNDLE\lib.
rem It does NOT reference the original project or the frozen target directories.
rmdir /S /Q "%CLASSES_DIR%" >nul 2>&1
rmdir /S /Q "%BUNDLE%\standalone-src" >nul 2>&1

echo.
echo [5/5] Running frozen probe...
echo ============================================================
echo Word root     : %WORD_ROOT%
echo Legacy schema : TSTSHMA
echo Threads       : 4
echo Expected docs : 5321
echo Fail on errors: false
echo Reports       : %REPORT_ROOT%
echo ============================================================
echo.

pushd "%BUNDLE%"

java ^
  -Dfile.encoding=UTF-8 ^
  -Dschemaforge.uk.probe.wordRoot="%WORD_ROOT%" ^
  -Dschemaforge.uk.probe.legacySchema=TSTSHMA ^
  -Dschemaforge.uk.probe.expectedMinDocuments=5321 ^
  -Dschemaforge.uk.probe.failOnErrors=false ^
  -Dschemaforge.uk.probe.threads=4 ^
  -Dschemaforge.uk.probe.progressEveryDocuments=100 ^
  -Dschemaforge.uk.probe.outputDir="%REPORT_ROOT%" ^
  -cp "%RUNNER_JAR%;%LIB_DIR%\*" ^
  com.behsazan.schemaforge.integration.LegacyUkProbeStandaloneMain

set "PROBE_RC=%ERRORLEVEL%"
popd

echo.
echo ============================================================
if "%PROBE_RC%"=="0" (
  echo PROBE COMPLETED SUCCESSFULLY
) else (
  echo PROBE FAILED - exit code %PROBE_RC%
)
echo Runner JAR:
echo   %RUNNER_JAR%
echo Reports:
echo   %REPORT_ROOT%
echo ============================================================
echo.

exit /b %PROBE_RC%


:WORKER_FAIL
echo.
echo ============================================================
echo Detached worker failed during preparation.
echo Bundle retained for inspection:
echo   %BUNDLE%
echo ============================================================
echo.
exit /b 10


:USAGE
echo.
echo Usage:
echo   %~nx0 "D:\path\to\word-corpus"
echo.
echo Example:
echo   %~nx0 "D:\get-git-doc-files-master"
echo.
echo The script snapshots D:\Projects\schema-forge-v4, returns control,
echo and continues the long probe in a separate low-priority CMD window.
echo.
exit /b 1
