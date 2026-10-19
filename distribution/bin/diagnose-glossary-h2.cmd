@echo off
setlocal EnableExtensions
pushd "%~dp0..\.." || exit /b 1

echo [INFO] Diagnosing isolated Atomic glossary H2 import and repeated search.
echo [INFO] Production database under data\ will not be opened by this test.
echo [INFO] HF6 GlossaryRepository.java expected SHA-256:
echo [INFO] 3f7caaea204ed475616d4a1162cd7f170a92f98250c6b063d7a07522710e600c
echo [INFO] Actual SHA-256 follows:
certutil -hashfile "src\main\java\com\behsazan\schemaforge\glossary\GlossaryRepository.java" SHA256
if errorlevel 1 (
  echo [ERROR] Unable to inspect Repository source.
  popd
  exit /b 1
)

call mvnw.cmd clean -Dtest=GlossaryAtomicSnapshotDiagnosticTest test
set "rc=%errorlevel%"
echo [INFO] Look for lines starting GLOSSARY_DIAG in the Maven console output.
popd
exit /b %rc%
