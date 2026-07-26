# SchemaForge V4 - Application Dialect Selection (Step 5)

## Scope

This step makes Oracle/PostgreSQL selection available from the offline application entry point without changing the canonical model or the DBMS-neutral generator.

## Added

- `application.DatabasePlatform`
- `application.DialectFactory`
- PostgreSQL and Oracle command-line selection
- Backward-compatible Oracle default
- Command-line selection tests

## Supported commands

```text
java -jar schema-forge.jar <input.docx>
java -jar schema-forge.jar <input.docx> <oracle|postgresql>
java -jar schema-forge.jar <input.docx> <output-directory> [oracle|postgresql]
```

Aliases:

- Oracle: `oracle`, `ora`
- PostgreSQL: `postgresql`, `postgres`, `pg`

## Compatibility

The original one-argument and two-argument output-directory invocations still generate Oracle DDL by default.

## Verification

- Java 21 compilation succeeded for domain, dialect, generation and application selection classes.
- Dialect selection smoke test succeeded.
- Full Maven execution requires Maven/dependencies available in the target environment.
