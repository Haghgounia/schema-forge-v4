# R6 Audit and Infrastructure Contract

## Scope

R6 hardens two independent generation contracts:

1. request-level audit-column enrichment for all artifact-generation REST endpoints;
2. DBMS-specific infrastructure/provisioning guidance in generated DDL.

## Audit request contract

Supported query parameters:

```text
includeAuditFields=true|false
auditProfile=AUTO|CREATED_UPDATED|CREATED_LAST_MODIFIED
```

Endpoints:

```text
POST /api/v1/generate/word
POST /api/v1/generate/legacy-word
POST /api/v1/generate/zip
POST /api/v1/generate/ea-xml
```

Profiles:

```text
CREATED_UPDATED
  CREATED_AT
  CREATED_BY
  UPDATED_AT
  UPDATED_BY

CREATED_LAST_MODIFIED
  CREATED_DATE
  CREATED_BY
  LAST_MODIFIED_DATE
  LAST_MODIFIED_BY
```

Rules:

- `includeAuditFields=false`: do not add audit columns.
- existing source audit columns are never replaced merely to match a profile.
- `AUTO`: detect the source convention and add only missing members of that convention.
- if the source model consistently uses one family, audit-free tables inherit that family.
- mixed families in the same table are rejected with `AUDIT_PROFILE_CONFLICT`.
- explicit profile conflicting with source evidence is rejected.
- request options are recorded in Standard Manifest V1 extensions.

The `CREATED_UPDATED` built-in missing-column shape is:

```text
CREATED_AT TIMESTAMP(6) NOT NULL
CREATED_BY VARCHAR2(50 CHAR) NOT NULL
UPDATED_AT TIMESTAMP(6) NULL
UPDATED_BY VARCHAR2(100 CHAR) NULL
```

These are canonical definitions; each target dialect renders its own physical datatype syntax.

## Default normalization

R6 conservatively fixes legacy character defaults such as:

```text
ACTIVE -> 'ACTIVE'
```

Only unquoted bare identifiers on character columns are normalized. Known SQL context expressions such as `CURRENT_TIMESTAMP`, `SYSDATE`, `SYSTIMESTAMP`, `USER`, `CURRENT_USER` and `NULL` are preserved. Already quoted values, numeric defaults, function calls and sequence expressions are preserved.

## Five-DBMS infrastructure guidance

Infrastructure concepts are DBMS-specific and are never normalized to an Oracle-style tablespace abstraction.

- Oracle: optional `TABLESPACE` template plus `CREATE USER`/schema-owner provisioning guidance.
- PostgreSQL: executable schema bootstrap; optional cluster-level `TABLESPACE` guidance only.
- Db2 for z/OS: commented `STOGROUP -> DATABASE -> TABLESPACE` provisioning template; no VCAT/volume/buffer-pool guessing.
- SQL Server: commented `FILEGROUP`/data-file template plus executable schema bootstrap; no tablespace terminology.
- MySQL: database/schema bootstrap; InnoDB file-per-table is the default guidance, with general tablespace only as an optional DBA-controlled template.

All environment-specific paths, file sizes, growth settings, volumes, VCAT names, buffer pools, filegroups and general tablespace names remain placeholders unless supplied by explicit physical design evidence/configuration.
