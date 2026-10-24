# UI-C9c — Optional Schema and JDBC Schema Discovery (Console V0.5.2)

**Baseline:** user-supplied `schema-forge-v4-2026-10-24-0748.zip` (UI 0.5.1). This is a scoped continuation of the existing Console roadmap; it does not replace the Glossary P1–P5 roadmap.

## Intended user workflow

1. A user selects any of the seven supported DBMSs and enters JDBC URL, username, and password. `Schema for metadata` is **optional**.
2. With a blank Schema, **Test & discover schemas** invokes the existing `POST /api/v1/connections/test` and receives the JDBC-selected/default namespace (where supported) and a bounded list of visible schema or catalog names. MySQL and MariaDB use JDBC catalogs; the other platforms use JDBC schemas.
3. The UI suggests discovered names in an editable field; a user can select one or continue without specifying a schema. Changing the JDBC URL, user, or password invalidates stale discovery suggestions. The password is cleared after each test/activation, so reentry is required for subsequent operations.
4. **Test metadata and use connection** invokes the existing `POST /api/v1/connections/activate`. An explicitly entered schema still gets the original strict vendor-specific `schemaExists` / `findTableNames` validation. An omitted schema requires successful JDBC catalog/schema enumeration. If the driver does not support discovery or returns no namespaces, the user receives an explicit failure and can enter a schema manually.
5. A no-schema selection has `verifiedSchema=""` and carries nonsecret `defaultSchema`, `availableSchemas` and `schemaListTruncated` hints. **No specific schema is presented as verified.** The same per-browser, 30-minute opaque token is used by Generation, Conformance and CRUD. Their subsequent explicit schema/table requests continue to be validated by the DBMS.
6. No changes to any startup DataSource, global repository, Canonical model, DDL generation, application.yml, or H2. The local-only safeguard on the existing connection administration controller remains in force. This is **not** a remotely accessible unauthenticated connection manager.

## REST compatibility

No routes added. Existing endpoints:

- `GET /api/v1/connections`
- `POST /api/v1/connections/test`
- `POST /api/v1/connections/activate`
- `GET /api/v1/connections/active`
- `DELETE /api/v1/connections/active`

`ProbeResult` and `Selection` add nullable/optional-compatible JSON fields `defaultSchema`, `availableSchemas`, `schemaListTruncated`. Existing fields and HTTP methods are retained. `verifiedSchema` is empty if the user did not request or verify a specific schema. Schema/catalog enumeration is a discovery hint and does not imply SELECT privilege on individual objects.

## Acceptance gates

| Gate | Expected evidence | State |
|---|---|---|
| UI-C9c-1 | With Schema empty, JDBC discovery succeeds for a supported DBMS | Pending Windows/live DBMS |
| UI-C9c-2 | Schema suggestions and default populate; user can also type manually | Pending browser |
| UI-C9c-3 | Activation with Schema empty returns a valid connection token; Conformance on an explicitly selected table reads this same DBMS | Pending live metadata |
| UI-C9c-4 | Explicit schema remains permission-checked; wrong schema fails closed | Pending Maven + live DBMS |
| UI-C9c-5 | Unsupported discovery and mismatched platform fail closed; no password or cross-user state leaked | Added Java and Node regression tests, Maven pending |
| UI-C9c-6 | `npm run test`, `npm run typecheck`, `npm run build`; focused Java Maven suite | Pending Windows |
| UI-C9c-7 | Original Glossary P1–P5, EA/Word outputs, prior H2, and the seven DBMS metadata repositories remain unchanged | Controlled source diff |

### Windows verification

```cmd
cd /d D:\Projects\schema-forge-v4
mvnw.cmd -Dtest=DatabaseConnectionAdministrationServiceTest,DatabaseConnectionControllerTest,JdbcSchemaDiscoveryTest,RuntimeMetadataConnectionRegistryTest,RuntimeMetadataResolverSelectionTest test
cd ui
npm install
npm run test
npm run typecheck
npm run build
npm run dev
```

Browser: connect to an **isolated, read-only test DBMS account**, leave Schema blank, test/discover, activate, and run Conformance for a known schema/table. Also test invalid schema and a second DBMS (no silent fallback). Never use production credentials or apply generated migrations as part of this gate.
