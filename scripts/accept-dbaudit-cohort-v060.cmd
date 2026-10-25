@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [SchemaForge] Read-only selected table cohort regression...
call mvnw.cmd -Dtest=SchemaConformanceCohortAuditServiceTest,SchemaConformanceCohortControllerTest,SchemaConformanceAuditServiceTest,SchemaConformanceSelectedControllerTest test
if errorlevel 1 (
  echo [SchemaForge] Cohort regression FAILED.
  exit /b 1
)

echo [SchemaForge] Cohort target regression PASSED.
echo [SchemaForge] Run 'mvnw.cmd clean test' to complete Maven acceptance.
echo [SchemaForge] Run 'cd ui ^&^& npm ci ^&^& npm test ^&^& npm run build' to validate updated UI.
echo [SchemaForge] Live Oracle FEE3 audit must be run separately through a selected read-only connection.
exit /b 0
