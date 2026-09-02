# SchemaForge V4 4.0.0 - Configuration

SchemaForge distribution configuration is read from `config/application.yml` by `scripts/start-windows.cmd`.

## Core runtime settings

| Setting | Environment variable | Distribution default | Purpose |
|---|---|---:|---|
| `server.port` | `SCHEMAFORGE_SERVER_PORT` | `9090` | REST server port |
| `spring.servlet.multipart.max-file-size` | n/a in shipped template | `100MB` | Maximum individual upload |
| `spring.servlet.multipart.max-request-size` | n/a in shipped template | `100MB` | Maximum multipart request |
| `springdoc.swagger-ui.path` | n/a | `/swagger-ui.html` | Swagger UI path |
| `springdoc.api-docs.path` | n/a | `/v3/api-docs` | OpenAPI JSON path |
| `schemaforge.numeric-mapping.strategy` | `SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY` | `SAFE` | Exact numeric mapping strategy |
| `schemaforge.ea.default-schema` | `SCHEMAFORGE_EA_DEFAULT_SCHEMA` | `COL` | EA fallback schema when XMI omits owner/schema |

`schemaforge.numeric-mapping.strategy` supports the enum values implemented by V4. The validated production default is `SAFE`.

## Audit field standard

`schemaforge.standards.audit.enabled` is controlled by:

```text
SCHEMAFORGE_AUDIT_ENABLED
```

Default: `true`.

The shipped audit column profile is:

```text
CREATED_BY          VARCHAR2(50) NOT NULL
CREATED_DATE        TIMESTAMP    NOT NULL
LAST_MODIFIED_BY    VARCHAR2(50) NOT NULL
LAST_MODIFIED_DATE  TIMESTAMP    NOT NULL
```

Generation requests can also select audit behavior through their frozen REST parameters `includeAuditFields` and `auditProfile`.

## Grants

`schemaforge.standards.grants` is empty in the distribution.

SchemaForge does not invent database users or roles. If grant rules are configured, `grantee` values must refer to principals provisioned by the target DBA/environment.

## Spell check

| Property | Environment variable | Default |
|---|---|---|
| `enabled` | `SCHEMAFORGE_SPELL_CHECK_ENABLED` | `false` in distribution |
| `endpoint` | `SCHEMAFORGE_SPELL_CHECK_ENDPOINT` | `https://api.languagetool.org/v2/check` |
| `language` | `SCHEMAFORGE_SPELL_CHECK_LANGUAGE` | `en-US` |
| `connect-timeout` | `SCHEMAFORGE_SPELL_CHECK_CONNECT_TIMEOUT` | `3s` |
| `request-timeout` | `SCHEMAFORGE_SPELL_CHECK_REQUEST_TIMEOUT` | `5s` |
| `maximum-suggestions` | `SCHEMAFORGE_SPELL_CHECK_MAXIMUM_SUGGESTIONS` | `3` |
| `fail-open` | `SCHEMAFORGE_SPELL_CHECK_FAIL_OPEN` | `true` |

The distribution disables spell check by default so startup and generation do not depend on an external Internet service.

## Live metadata profiles

Every database profile uses the same five settings:

```text
enabled
url
username
password
driver-class-name
```

When a profile is enabled, URL, username, and driver class must be configured. Password may be empty only if the target database authentication scheme permits it.

### Oracle

```text
SCHEMAFORGE_METADATA_ORACLE_ENABLED
SCHEMAFORGE_METADATA_ORACLE_URL
SCHEMAFORGE_METADATA_ORACLE_USERNAME
SCHEMAFORGE_METADATA_ORACLE_PASSWORD
Driver: oracle.jdbc.OracleDriver
```

### PostgreSQL

```text
SCHEMAFORGE_METADATA_POSTGRESQL_ENABLED
SCHEMAFORGE_METADATA_POSTGRESQL_URL
SCHEMAFORGE_METADATA_POSTGRESQL_USERNAME
SCHEMAFORGE_METADATA_POSTGRESQL_PASSWORD
Driver: org.postgresql.Driver
```

### SQL Server

```text
SCHEMAFORGE_METADATA_SQLSERVER_ENABLED
SCHEMAFORGE_METADATA_SQLSERVER_URL
SCHEMAFORGE_METADATA_SQLSERVER_USERNAME
SCHEMAFORGE_METADATA_SQLSERVER_PASSWORD
Driver: com.microsoft.sqlserver.jdbc.SQLServerDriver
```

### MySQL

```text
SCHEMAFORGE_METADATA_MYSQL_ENABLED
SCHEMAFORGE_METADATA_MYSQL_URL
SCHEMAFORGE_METADATA_MYSQL_USERNAME
SCHEMAFORGE_METADATA_MYSQL_PASSWORD
Driver: com.mysql.cj.jdbc.Driver
```

### Db2 LUW

```text
SCHEMAFORGE_METADATA_DB2LUW_ENABLED
SCHEMAFORGE_METADATA_DB2LUW_URL
SCHEMAFORGE_METADATA_DB2LUW_USERNAME
SCHEMAFORGE_METADATA_DB2LUW_PASSWORD
Driver: com.ibm.db2.jcc.DB2Driver
```

The standard GA JAR was built without the optional `db2luw-live` Maven profile, therefore IBM JCC is not bundled in the standard distribution binary. Keep this profile disabled.

### Db2 z/OS

```text
SCHEMAFORGE_METADATA_DB2ZOS_ENABLED
SCHEMAFORGE_METADATA_DB2ZOS_URL
SCHEMAFORGE_METADATA_DB2ZOS_USERNAME
SCHEMAFORGE_METADATA_DB2ZOS_PASSWORD
Driver: com.ibm.db2.jcc.DB2Driver
```

The standard GA JAR does not bundle the environment-specific Db2 z/OS JCC dependency. Keep this profile disabled in the standard runtime distribution.

## Safe deployment rule

Do not add real passwords to `config/application.yml` when the directory is copied, archived, emailed, or checked into source control. Prefer environment variables populated by the deployment platform or a secrets manager.
