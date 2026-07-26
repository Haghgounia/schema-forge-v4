# EA XML Verification — 2026-07-26

Input: `EA.xml` exported by Enterprise Architect 2.5.

Standalone parser and DDL verification results:

```text
Schema (configured): FEE
Tables              : 28
Columns             : 177
Primary keys        : 28
Foreign keys        : 24
Indexes             : 27
Unique keys         : 0
```

Both Oracle and PostgreSQL DDL generation completed from the imported canonical model:

```text
Oracle CREATE TABLE : 28
Oracle FOREIGN KEY  : 24
Oracle CREATE INDEX : 27
```

The supplied EA model does not contain explicit unique-key, unique-index, or check-constraint operations. The importer supports those stereotypes for future EA exports.
