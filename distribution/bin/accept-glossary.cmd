@echo off
setlocal EnableExtensions
set "SCHEMAFORGE_PROJECT_ROOT=%~dp0..\.."
cd /d "%SCHEMAFORGE_PROJECT_ROOT%" || exit /b 1

echo [INFO] Running isolated Glossary operational and governance acceptance tests.
echo [INFO] Tests use temporary H2 files, not data\schemaforge-glossary.mv.db.
call mvnw.cmd -Dtest=GlossaryOperationalAcceptanceTest,GlossaryReviewWorkflowTest,GlossaryReportCsvParserTest,GlossaryRealSourceAcceptanceTest,GlossaryAtomicBootstrapTest,GlossaryAtomicSnapshotDiagnosticTest,GlossaryH2VersionContractTest,GlossaryH2AndReportTest,GlossaryRestControllerTest,GlossaryStartupWiringTest test
if errorlevel 1 (
    echo [ERROR] Glossary acceptance tests failed.
    exit /b 1
)
echo [INFO] Glossary offline acceptance gate passed.
if /I "%~1"=="--live" (
    echo [INFO] Checking running Glossary REST and OpenAPI on localhost:9090.
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCHEMAFORGE_PROJECT_ROOT%\distribution\bin\check-glossary-live.ps1" -BaseUrl "http://localhost:9090"
    if errorlevel 1 exit /b 1
)
echo [INFO] For a separate running service check: distribution\bin\accept-glossary.cmd --live
endlocal
exit /b 0
