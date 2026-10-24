@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [V0.5.7] Canonical/DDL seven DBMS + frontend + initial bundle budget...
call "%~dp0accept-console-v056.cmd"
if errorlevel 1 (
  echo [FAIL] UI-C11 acceptance gate failed.
  exit /b 1
)
echo [PASS] V0.5.7 focused regression and lazy bundle audit completed.
exit /b 0
