@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem SchemaForge V4: move known generated root artifacts using CMD only.
rem No PowerShell, Python, Maven, Java, database access, or deletes.

set "DRYRUN=0"
if /I "%~1"=="/dry-run" set "DRYRUN=1"
if not "%~1"=="" if /I not "%~1"=="/dry-run" (
    echo [ERROR] Usage: fix-root.cmd [/dry-run]
    exit /b 2
)

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if not exist "%PROJECT_ROOT%\pom.xml" (
    echo [ERROR] Missing pom.xml in "%PROJECT_ROOT%"
    exit /b 2
)
if not exist "%PROJECT_ROOT%\src\" (
    echo [ERROR] Missing src directory in "%PROJECT_ROOT%"
    exit /b 2
)
pushd "%PROJECT_ROOT%"
if errorlevel 1 (
    echo [ERROR] Cannot access "%PROJECT_ROOT%"
    exit /b 2
)

echo [INFO] SchemaForge root: "%PROJECT_ROOT%"
if "%DRYRUN%"=="1" echo [INFO] Preview only - no file will be moved.
set /a FOUND=0,MOVED=0,FAILED=0 >nul

call :MoveOne "ea-performance.log" "logs\ea-performance"
call :MoveOne "ea-performance-offline.log" "logs\ea-performance"
call :MoveOne "ea-performance-no-spell.log" "logs\ea-performance"
call :MoveOne "ea-performance-offline-spelling.log" "logs\ea-performance"
call :MoveOne "ea-performance-summary.csv" "logs\ea-performance"
call :MoveOne "v4145-auto.log" "logs\glossary"
call :MoveOne "v4145-dry-run.log" "logs\glossary"
call :MoveOne "glossary-acceptance.xlsx" "test-output\glossary"
call :MoveOne "ea-perf-all.zip" "test-output\ea"
call :MoveOne "ea-perf-oracle.zip" "test-output\ea"

echo.
echo [SUMMARY] Found=%FOUND% Moved=%MOVED% Failed=%FAILED%
echo [ROOT FILES]
dir /a:-d /b
if "%DRYRUN%"=="1" (
    echo [PREVIEW ONLY] No changes made.
) else (
    if "%FAILED%"=="0" echo [OK] Root cleanup completed.
)
popd
if not "%FAILED%"=="0" exit /b 1
exit /b 0

:MoveOne
set "SRC=%~1"
if not exist "%SRC%" exit /b 0
set /a FOUND+=1 >nul
set "DESTDIR=%~2"
set "TARGET=%DESTDIR%\%~1"
if exist "%TARGET%" (
    call :PickFreeTarget "%~n1" "%~x1" "%DESTDIR%"
    if errorlevel 1 (
        echo [ERROR] No free destination for "%SRC%"
        set /a FAILED+=1 >nul
        exit /b 1
    )
)
if "%DRYRUN%"=="1" (
    echo [WOULD MOVE] "%SRC%" to "%TARGET%"
    exit /b 0
)
if not exist "%DESTDIR%\" mkdir "%DESTDIR%" >nul 2>&1
if not exist "%DESTDIR%\" (
    echo [ERROR] Cannot create "%DESTDIR%"
    set /a FAILED+=1 >nul
    exit /b 1
)
move /-Y "%SRC%" "%TARGET%" >nul
if errorlevel 1 (
    echo [ERROR] Move failed: "%SRC%" to "%TARGET%"
    set /a FAILED+=1 >nul
    exit /b 1
)
if exist "%SRC%" (
    echo [ERROR] Source still exists: "%SRC%"
    set /a FAILED+=1 >nul
    exit /b 1
)
if not exist "%TARGET%" (
    echo [ERROR] Destination not found: "%TARGET%"
    set /a FAILED+=1 >nul
    exit /b 1
)
echo [MOVED] "%SRC%" to "%TARGET%"
set /a MOVED+=1 >nul
exit /b 0

:PickFreeTarget
set "PICK_BASE=%~1"
set "PICK_EXT=%~2"
set "PICK_DIR=%~3"
set /a PICK_INDEX=1 >nul
:PickNext
if %PICK_INDEX% GTR 9999 exit /b 1
set "TARGET=%PICK_DIR%\%PICK_BASE%-from-root-%PICK_INDEX%%PICK_EXT%"
if not exist "%TARGET%" exit /b 0
set /a PICK_INDEX+=1 >nul
goto PickNext
