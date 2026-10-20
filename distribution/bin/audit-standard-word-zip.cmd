@echo off
setlocal EnableExtensions
rem Read-only Glossary audit of STANDARD DOCX files inside an external ZIP.
rem Produces schemaforge-glossary-word-audit-review.xlsx (includes Persian field name from DOCX).
rem Usage: audit-standard-word-zip.cmd "D:\Input\standard-word.zip" ["D:\Output\reports"] ["D:\Project\data\schemaforge-glossary"]
cd /d "%~dp0\..\.." || exit /b 1
if "%~1"=="" (
    echo [ERROR] ZIP path is required.
    echo Usage: call distribution\bin\audit-standard-word-zip.cmd "D:\Input\standard-word.zip" ["D:\Reports"] ["D:\Projects\schema-forge-v4\data\schemaforge-glossary"]
    exit /b 2
)
set "WORD_ZIP=%~f1"
if not exist "%WORD_ZIP%" (
    echo [ERROR] ZIP does not exist: %WORD_ZIP%
    exit /b 2
)
set "AUDIT_REPORT_ROOT=%CD%\target\glossary-standard-word-zip"
if not "%~2"=="" set "AUDIT_REPORT_ROOT=%~f2"
set "AUDIT_H2_BASE=%CD%\data\schemaforge-glossary"
if not "%~3"=="" set "AUDIT_H2_BASE=%~f3"
if not exist "%AUDIT_H2_BASE%.mv.db" (
    echo [ERROR] Glossary H2 is missing: %AUDIT_H2_BASE%.mv.db
    echo [INFO] Enable Glossary and bootstrap H2 before running this read-only audit.
    exit /b 3
)
echo [INFO] Standard DOCX ZIP: %WORD_ZIP%
echo [INFO] Glossary H2 read-only: %AUDIT_H2_BASE%.mv.db
echo [INFO] Output root: %AUDIT_REPORT_ROOT%
echo [INFO] Excel in each audit run: reports\glossary\schemaforge-glossary-word-audit-review.xlsx
call mvnw.cmd -Dschemaforge.glossary.wordZip.file="%WORD_ZIP%" -Dschemaforge.glossary.wordZip.h2Base="%AUDIT_H2_BASE%" -Dschemaforge.glossary.wordZip.outputDir="%AUDIT_REPORT_ROOT%" -Dschemaforge.glossary.wordZip.required=true -Dtest=GlossaryStandardWordZipAuditTest test
exit /b %ERRORLEVEL%
