# Audit Timestamp and Persian Names (2026-10-20)

Base: `schema-forge-v4-2026-10-20-glossary-six-sheet-persian-review-full.zip`.

## User requirement

`schemaforge.standards.audit.columns` includes `CREATED_BY`, `CREATED_DATE`,
`LAST_MODIFIED_BY`, `LAST_MODIFIED_DATE`. Newly synthesized audit columns declared
as unqualified `TIMESTAMP` must respect the REST request `timestampSemantics`.
Audit field definitions must also carry Persian names, independent of the existing
English `comment`.

## Resulting YAML

```yaml
schemaforge:
  standards:
    audit:
      enabled: true
      columns:
        - name: CREATED_BY
          dataType: VARCHAR2(50)
          nullable: false
          comment: "Creation user"
          persianName: "کاربر ایجادکننده"
        - name: CREATED_DATE
          dataType: TIMESTAMP
          nullable: false
          comment: "Creation timestamp"
          persianName: "تاریخ و زمان ایجاد"
        - name: LAST_MODIFIED_BY
          dataType: VARCHAR2(50)
          nullable: false
          comment: "Last modification user"
          persianName: "کاربر آخرین تغییر"
        - name: LAST_MODIFIED_DATE
          dataType: TIMESTAMP
          nullable: false
          comment: "Last modification timestamp"
          persianName: "تاریخ و زمان آخرین تغییر"
```

These definitions are also present in `src/main/resources/application.yml`,
`distribution/config/application.yml` and `distribution/config/application-example.yml`.
Spring Boot binds `persianName` to `AuditProperties.AuditColumn.persianName`.

## Semantics

1. Audit enrichment first adds **missing** members of the requested or detected audit family.
2. The common Timestamp Semantics Enricher then rewrites only unqualified canonical `TIMESTAMP`,
   including `TIMESTAMP(6)`, according to the request's `timestampSemantics` selection.
3. `source` / `without-time-zone` leave unqualified timestamps unchanged.
4. Explicit `TIMESTAMP_WITH_TIME_ZONE`, `TIMESTAMP_WITH_LOCAL_TIME_ZONE`,
   `DATE` or other user-authored types remain unchanged.
5. The source audit naming family still overrides a conflicting request profile;
   no extra or duplicate columns are inserted. Audit columns are not added if `auditFields=source` or `none`.
6. The `created-updated` built-in profile also includes Persian labels and obeys the same timestamp selection.
7. DBMS mappings preserve safety policy: Oracle supports both timezone forms, SQL Server
   renders timezone-aware types as DATETIMEOFFSET, PostgreSQL renders WITH TIME ZONE,
   while unsupported/lossy dialect cases may block output or report compatibility issues.

## Persian metadata representation

`persianName` and English `comment` are kept as separate values in configuration.
The current `Column` canonical record has one `description` field, not a separate
column PersianName. The newly synthesized column therefore receives
`<persianName> | <comment>` as its canonical description so that both labels reach
canonical JSON, DDL COMMENT/extended-property statements, and existing reports.
Older custom YAML without `persianName` remains supported: its English `comment`
is kept unchanged. Source-declared audit column descriptions are not overwritten.

## Acceptance tests

```cmd
cd /d D:\Projects\schema-forge-v4

mvnw.cmd clean ^
  -Dtest=TimestampSemanticsPreparationTest,TimestampSemanticsSchemaEnricherTest,AuditColumnSchemaEnricherTest,AuditPropertiesBindingTest,AuditTimestampPersianDdlTest,AuditColumnsAllDialectsTest ^
  test

mvnw.cmd clean test
```

NOTE: run the above with the actual Spring, Jackson, and JUnit dependencies on Windows.
The assistant's environment supports only partial local Java compilation and
isolated stub-backed probes, not a full Maven/H2 integration run.
