# SchemaForge V4 - Dialect Capability Step 4

## Implemented

- Added `DialectFeature` as the stable capability contract for optional DBMS features.
- Added `supportedFeatures()`, `supports(...)`, and `require(...)` to `Dialect`.
- Declared Oracle and PostgreSQL capabilities explicitly.
- Updated `DdlGenerator` to use capabilities for:
  - sequences
  - identity columns
  - generated columns
  - table comments
  - column comments
  - grants
- Added `DialectCapabilityTest`.
- Confirmed that `generation` has no dependency on OracleDialect or PostgreSqlDialect.
- Confirmed Java 21 compilation of domain, dialect, and generation packages.

## Verification note

The Maven wrapper could not download Maven in the isolated build environment. Run `mvn test` or `mvnw test` in the normal development environment. The expected test count is 14 after adding `DialectCapabilityTest`.
