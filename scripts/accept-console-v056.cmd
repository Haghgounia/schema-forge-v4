@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

echo [1/2] Canonical Model, DDL generators for seven DBMSs, and metadata routing...
call mvnw.cmd -Dtest=CanonicalSnapshotMapperTest,UnresolvedCanonicalDatatypeGenerationTest,OracleDdlGeneratorTest,PostgreSqlDdlGeneratorTest,SqlServerDdlGeneratorTest,MySqlDdlGeneratorTest,MariaDbDdlGeneratorTest,Db2LuwDdlGeneratorTest,Db2ZosDdlGeneratorTest,IntegratedSqlRendererTest,MetadataRepositoryResolverTest test
if errorlevel 1 (
  echo [FAIL] Backend regression tests failed. No acceptance granted.
  exit /b 1
)

echo [2/2] React API, ZIP, Graphviz/Mermaid and Glossary evidence regressions...
cd ui
if errorlevel 1 exit /b 1
call npm run test
if errorlevel 1 exit /b 1
call npm run typecheck
if errorlevel 1 exit /b 1
call npm run build
if errorlevel 1 exit /b 1

echo [PASS] Targeted backend and frontend regression gates completed.
exit /b 0
