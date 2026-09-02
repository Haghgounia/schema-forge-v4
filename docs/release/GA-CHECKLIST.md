# SchemaForge V4 4.0.0 - GA Checklist

## Promotion rule

- [x] RC1 production behavior frozen
- [x] RC1 full regression green
- [x] RC1 executable JAR built successfully
- [x] RC1 SHA-256 recorded
- [x] RC1 live SQL Server Schema Conformance smoke passed
- [x] RC1 `/v3/api-docs` smoke passed
- [x] GA promotion contains no production Java changes
- [x] Maven project version changed from `4.0.0-RC1` to `4.0.0`

## Final GA verification

- [ ] `mvnw.cmd clean package` -> BUILD SUCCESS
- [ ] Tests -> 743 / 0 failures / 0 errors
- [ ] `target\schema-forge-v4-4.0.0.jar` exists
- [ ] GA JAR SHA-256 recorded
- [ ] Start exact GA JAR
- [ ] `GET /v3/api-docs` passes
- [ ] SQL Server live Schema Conformance endpoint passes
- [ ] `reportContract = schemaforge-schema-conformance/v3`

## Deferred

- [ ] Db2 z/OS live execution validation when environment becomes available
