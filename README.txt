SchemaForge Oracle Offline DDL Patch

Scope:
- Generates one complete Oracle SQL script from DatabaseSchema.
- Does not connect to Oracle or any other database.
- Uses the canonical model only.
- Output order: sequences, create table + PK, checks, unique keys, foreign keys,
  indexes, comments, grants, Gregorian timestamp footer.
- Oracle VARCHAR2/CHAR lengths are rendered with CHAR semantics.

Apply from the project root:
  patch -p5 < schema-forge-v4-oracle-offline.patch

Alternatively copy the three Java files under src/main/java/com/behsazan/schemaforge.

Verification performed:
  javac compilation of the complete domain model plus the three changed classes.
