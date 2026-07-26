# Metadata Validation - Phase 2 (Step 1)

Implemented repository layer:

- `MetadataRepository`
- `OracleMetadataProvider`
- `PostgreSqlMetadataProvider`
- `JsonMetadataProvider`
- `InMemoryMetadataRepository`

Implemented first two behaviors:

1. Aggregate field-name frequency rendered before the column:

```sql
/*  60*/  CUSTOMER_ID NUMBER(10) NOT NULL,
```

2. Database metadata type comparison. A mismatch is rendered in the validation header and inline as `W:TYPE`.

When a field name has multiple database type definitions, the header reports all type signatures and frequencies, without table names:

```text
Metadata frequencies: NUMBER(10) [60], VARCHAR2(20) [15]. Total occurrences: 75.
```

## JSON metadata format

```json
{
  "columns": [
    {
      "name": "CUSTOMER_ID",
      "totalFrequency": 75,
      "types": [
        { "signature": "NUMBER(10)", "frequency": 60 },
        { "signature": "VARCHAR2(20)", "frequency": 15 }
      ]
    }
  ]
}
```

## Generator usage

```java
MetadataRepository metadata = new JsonMetadataProvider(Path.of("metadata.json"));
String sql = new DdlGenerator(new OracleDialect())
        .generate(schema, validationReport, metadata);
```

Oracle and PostgreSQL providers use aggregated queries and never expose table names in findings.
