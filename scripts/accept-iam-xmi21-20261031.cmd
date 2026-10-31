@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 exit /b 1

if "%~1"=="" (
  echo [SchemaForge] Usage: call scripts\accept-iam-xmi21-20261031.cmd "D:\path\IAM-TARGET-R0.9.xmi"
  exit /b 2
)
set "IAM_XMI=%~f1"
if not exist "%IAM_XMI%" (
  echo [SchemaForge] IAM XMI input not found: "%IAM_XMI%"
  exit /b 2
)

echo [SchemaForge] IAM XMI 2.1 targeted parser and seven-dialect offline DDL acceptance...
call mvnw.cmd "-Dschemaforge.iam.xmi=%IAM_XMI%" "-Dtest=EnterpriseArchitectUml21OwnedRuleRegressionTest,EnterpriseArchitectIamXmi21CorpusIT,EnterpriseArchitectXmlParserTest,EaFceDdlV060RegressionTest" test
if errorlevel 1 (
  echo [SchemaForge] IAM targeted acceptance FAILED.
  exit /b 1
)

echo [SchemaForge] IAM targeted acceptance PASSED. Run mvnw.cmd clean test as a separate regression gate.
echo [SchemaForge] Live DBMS execution is NOT covered by this script.
exit /b 0
