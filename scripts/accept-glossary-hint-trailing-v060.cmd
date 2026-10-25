@echo off
setlocal
cd /d "%~dp0.."
echo [SchemaForge] Glossary trailing hint V0.6.0 regression tests...
call mvnw.cmd -Dtest=GlossaryDdlHintsTest,GlossaryDdlHintsApiIntegrationTest,GlossaryPluralNamingPolicyTest,EaFceDdlV060RegressionTest,EaGenerationOrchestratorTest,SchemaForgeEaPerTableOutputTest test
if errorlevel 1 (
    echo [SchemaForge] Glossary trailing hint acceptance FAILED.
    exit /b 1
)
echo [SchemaForge] Glossary trailing hint targeted tests PASSED.
exit /b 0
