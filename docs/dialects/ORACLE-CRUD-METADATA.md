# Oracle Metadata-Based CRUD Package Generation

SchemaForge can generate one Oracle PL/SQL CRUD package directly from the Oracle data dictionary. This capability is independent from Word, ZIP, and Enterprise Architect input.

## REST endpoint

```http
POST /api/v1/generate/oracle/crud
Content-Type: application/json
Accept: application/sql
```

Request:

```json
{
  "schema": "BIM",
  "table": "PROVINCES"
}
```

Response artifact:

```text
BIM.PROVINCES.oracle.crud-package.sql
```

## Metadata source

The request is resolved through the configured Oracle metadata repository and `JdbcOracleMetadataRepository.findTable(schema, table)`. The generator uses the canonical live `Table` model populated from Oracle catalog views, including:

- table and column metadata
- column order, nullability and defaults
- identity and virtual-column flags
- primary and unique keys
- foreign keys, checks and indexes already supported by the repository

No Word parser, specification enrichment, or document validation is involved.

## Generated package

For table `BIM.PROVINCES`, the default package name is:

```text
BIM.PKG_PROVINCES
```

The public API contains:

```text
CREATE_ROW
UPDATE_ROW
DELETE_ROW
GET_BY_ID
SEARCH
```

The package follows these rules:

- `AUTHID DEFINER` is explicit.
- Parameter types use `SCHEMA.TABLE.COLUMN%TYPE`.
- identity columns and primary-key columns backed by `DEFAULT ... NEXTVAL` are omitted from insert inputs.
- generated keys are returned with `RETURNING ... INTO`.
- virtual/generated columns are excluded from insert and update.
- primary-key columns are excluded from update assignments.
- audit time columns use `SYSDATE` for Oracle `DATE` and `SYSTIMESTAMP` for timestamp datatypes.
- configured audit-user columns are populated from the package actor parameter.
- `UPDATE_ROW` and `DELETE_ROW` raise an application error when no row is affected.
- duplicate keys and child-record delete violations are mapped to application errors.
- `SEARCH` uses PK, unique-key, and common status columns as optional filters.
- `SEARCH` has deterministic PK ordering and bounded pagination.
- no generated operation performs `COMMIT`, `ROLLBACK`, or autonomous transaction control.
- configured standard grantees receive `EXECUTE` on the package.

## Configuration

Oracle metadata must be enabled:

```yaml
schemaforge:
  metadata:
    oracle:
      enabled: true
      url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
      username: SYSTEM
      password: change-me
      driver-class-name: oracle.jdbc.OracleDriver
```

The configured standard grant principals are reused as package execute grantees:

```yaml
schemaforge:
  standards:
    grants:
      - grantee: U_DEVELOPER
        privileges: [SELECT, INSERT, UPDATE, DELETE]
      - grantee: U_DESIGNER
        privileges: [SELECT, INSERT, UPDATE, DELETE]
```

Only configured principals with at least one write privilege (`INSERT`, `UPDATE`, or `DELETE`) are reused. The generated package privilege is always `EXECUTE`; read-only principals are not promoted to CRUD execution.

## Validation constraints

Generation is rejected when:

- Oracle metadata is disabled;
- schema or table name is invalid;
- the table does not exist;
- the metadata table is not schema-qualified;
- the table has no primary key;
- no column can be updated;
- no explicit insert column can be generated.

The first phase supports Oracle identity columns and sequence-backed defaults such as:

```sql
DEFAULT BIM.SEQ_PROVINCES.NEXTVAL
```

Sequence assignment performed only by a trigger is not inferred in this phase.
