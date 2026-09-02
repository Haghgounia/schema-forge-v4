# Schema Conformance Audit 15.3

Adds live metadata conformance coverage for:
- KEY_CONSTRAINTS
  - TABLE_PRIMARY_KEY_MISSING (WARNING)
  - PRIMARY_KEY_COLUMN_NULLABLE (ERROR)
- REFERENTIAL_INTEGRITY
  - FK_REFERENCED_COLUMN_NOT_FOUND (ERROR)
  - FK_TARGET_NOT_UNIQUE (ERROR)
- INDEX_COVERAGE
  - PHYS-FK-INDEX-001 (INFO), aligned with the existing DDL Generator recommendation code

Report contract: schemaforge-schema-conformance/v3
Endpoints remain unchanged and read-only.
