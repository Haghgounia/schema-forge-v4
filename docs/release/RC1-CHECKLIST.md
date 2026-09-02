# SchemaForge V4 RC1 - Release Checklist

## Frozen gates

- [x] Functional output audit
- [x] Schema Conformance Audit foundation and report contract
- [x] Schema Conformance FK/key/index rules
- [x] SQL Server live Schema Conformance audit
- [x] API contract freeze
- [x] Final cross-contract regression: 743 tests, 0 failures, 0 errors
- [x] Executable Spring Boot JAR package
- [x] Executable-JAR E2E against `/v3/api-docs`
- [x] Executable-JAR E2E against live SQL Server Schema Conformance
- [x] Release Candidate version promotion to `4.0.0-RC1`
- [ ] Build exact `schema-forge-v4-4.0.0-RC1.jar`
- [ ] Record SHA-256 of exact RC1 JAR
- [ ] Smoke exact RC1 JAR

## Exact final commands

```bat
cd /d D:\Projects\schema-forge-v4

mvnw.cmd clean package

certutil -hashfile target\schema-forge-v4-4.0.0-RC1.jar SHA256
```

Start the exact RC1 artifact:

```bat
java -jar target\schema-forge-v4-4.0.0-RC1.jar ^
  --schemaforge.metadata.sqlserver.url="jdbc:sqlserver://localhost:1433;databaseName=schemaforge_test;encrypt=true;trustServerCertificate=true" ^
  --schemaforge.metadata.sqlserver.username=sa ^
  --schemaforge.metadata.sqlserver.password=sa@123456
```

From a second terminal:

```bat
curl.exe --fail-with-body -sS http://localhost:9090/v3/api-docs -o target\rc1-api-docs.json

curl.exe --fail-with-body -sS ^
  "http://localhost:9090/api/v1/conformance/schema?platform=sqlserver&schema=TSTSHMA" ^
  -o target\rc1-conformance.json

findstr /C:"schemaforge-schema-conformance/v3" target\rc1-conformance.json
```

When these final three unchecked items are complete, SchemaForge V4 RC1 is release-candidate frozen. Db2 z/OS live execution remains separately deferred until the environment exists.
