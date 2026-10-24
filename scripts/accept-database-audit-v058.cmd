@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [V0.5.8 1/2] Backend selected-target authentication, conformance and metadata identity...
call mvnw.cmd -Dtest=SchemaConformanceSelectedControllerTest,SchemaConformanceControllerTest,SchemaConformanceAuditServiceTest,RuntimeMetadataConnectionRegistryTest,DatabaseConnectionAdministrationServiceTest,DatabaseConnectionControllerTest,MetadataRepositoryResolverTest test
if errorlevel 1 (
  echo [FAIL] Explicit database audit backend contract tests.
  exit /b 1
)

echo [V0.5.8 2/2] Canonical and seven DBMS DDL safeguards, frontend and lazy bundle gate...
call "%~dp0accept-console-v057.cmd"
if errorlevel 1 (
  echo [FAIL] Regression gate failed.
  exit /b 1
)
echo [PASS] V0.5.8 targeted regression gates completed. Live DBMS browser acceptance remains separate.
exit /b 0
