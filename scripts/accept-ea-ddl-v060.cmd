@echo off
setlocal
cd /d "%~dp0.." || exit /b 1
call mvnw.cmd "-Dtest=EaFceDdlV060RegressionTest,PostgreSqlNumericIdentityV060Test,OracleDdlGeneratorTest,PostgreSqlDdlGeneratorTest,PostgreSqlFeatureParityTest,SchemaDiffEngineTest,MigrationSqlRendererTest,SchemaCompareExcelWriterTest,Db2ZosDdlGeneratorTest,LogicalObjectNamingPolicyTest,SpecificationNormalizerNamingContractTest,SchemaForgeLegacyWordApiServiceTest" test
if errorlevel 1 (
  echo [FAIL] V0.6.0 EA/Word DDL regression tests failed.
  exit /b 1
)
echo [PASS] V0.6.0 EA/Word DDL regression tests succeeded.
exit /b 0
