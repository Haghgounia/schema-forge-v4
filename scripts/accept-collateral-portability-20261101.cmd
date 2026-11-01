@echo off
setlocal EnableExtensions
pushd "%~dp0.."
if errorlevel 1 exit /b 1
if not exist mvnw.cmd (
  echo [FAIL] mvnw.cmd not found in SchemaForge root.
  popd
  exit /b 2
)
echo [COL-R1] Running offline source regression suite...
call mvnw.cmd "-Dtest=CollateralTemporalRegexPortabilityTest,EnterpriseArchitectXmlParserTest,MySqlTypeMapperTest,MariaDbTypeMapperTest,SqlServerExpressionMapperTest,PostgreSqlDdlGeneratorTest" test
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo [FAIL] COL-R1_REGRESSION_FAILED exit=%RC%
) else (
  echo [PASS] COL-R1_REGRESSION_PASS
)
popd
exit /b %RC%
