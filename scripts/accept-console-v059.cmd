@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [V0.5.9 1/2] Glossary approved baseline, configured connections and selected CRUD...
call mvnw.cmd -Dtest=GlossaryAtomicApprovalMigrationTest,GlossaryAtomicBootstrapTest,GlossaryOfflineH2ScriptTest,GlossaryStartupWiringTest,DatabaseConnectionAdministrationServiceTest,DatabaseConnectionControllerTest,SelectedCrudConnectionGuardTest,OracleCrudGenerationServiceTest,SqlServerCrudGenerationServiceTest,SchemaConformanceSelectedControllerTest test
if errorlevel 1 (
  echo [FAIL] V0.5.9 focused backend regression suite.
  exit /b 1
)

echo [V0.5.9 2/2] Previous audit, Canonical and 7-DBMS regression; all UI Node, TS, Vite...
call "%~dp0accept-database-audit-v058.cmd"
if errorlevel 1 (
  echo [FAIL] Previous baseline regression suite.
  exit /b 1
)

echo [PASS] Console V0.5.9 local acceptance. Live DBMS tests remain separate.
exit /b 0
