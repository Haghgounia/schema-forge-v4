@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [SchemaForge] EA JSON/Oracle V0.6.0 targeted regression tests...
call mvnw.cmd -Dtest=EaFceDdlV060RegressionTest,EaGenerationOrchestratorTest,SqlServerTypeMapperTest,IntegratedSqlRendererTest,OracleDdlSanityCheckerTest,SchemaForgeEaPerTableOutputTest,RunAllOrderingAcceptanceTest test
if errorlevel 1 (
    echo [SchemaForge] Targeted acceptance FAILED.
    exit /b 1
)

echo [SchemaForge] Full test suite...
call mvnw.cmd clean test
if errorlevel 1 (
    echo [SchemaForge] Full acceptance FAILED.
    exit /b 1
)

echo [SchemaForge] Maven acceptance PASSED. Live DBMS tests remain a separate gate.
exit /b 0
