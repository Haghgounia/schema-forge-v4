# SchemaForge V4 - EA/MySQL AUTO_INCREMENT supporting-index recovery R4

Status: **REPAIR CANDIDATE / PENDING MAVEN REGRESSION**

## Trigger

Real EA REST input `Party-Operation_Froms-14050601.xml` reached MySQL generation with an explicit
`AutoNum=True` column on `COL.PARTY_STATUS_HISTORY.PARTY_STATUS_HISTORY_ID`, but that EA table has
no PK/index operation that can make the identity column the leading indexed column at CREATE TABLE
time. SchemaForge therefore rejected the table before rendering MySQL DDL.

The same real XMI contains seven explicit AutoNum tables with no PK/index operations, so this is a
format/content portability condition rather than a one-table special case.

## Repair policy

1. Explicit/canonical identity intent is preserved; SchemaForge does not silently remove AUTO_INCREMENT.
2. Canonical PK/UK semantics are not invented or rewritten.
3. When the AUTO_INCREMENT column is not the first column of the CREATE-time primary key, MySQL DDL
   receives a target-only non-unique supporting KEY on that identity column.
4. The supporting KEY is emitted inside CREATE TABLE because a later CREATE INDEX/ALTER UNIQUE is too
   late to satisfy InnoDB AUTO_INCREMENT creation requirements.
5. The generated SQL carries DBA-visible marker `MYSQL-AUTO-INDEX-001` and states that canonical key
   semantics are unchanged.
6. If the primary key already begins with the identity column, no supporting index is added.
7. The canonical model is not mutated; Oracle/PostgreSQL/Db2 z/OS/SQL Server output is unchanged.

## Production scope

- `dialect/Dialect.java` - additive CREATE TABLE supplemental-definition hook.
- `generation/DdlGenerator.java` - inserts dialect supplemental definitions with stable comma handling.
- `dialect/mysql/MySqlDialect.java` - emits deterministic MySQL supporting KEY and keeps strict
  one-AUTO_INCREMENT/type validation.

## Regression coverage

`MySqlAutoIncrementSupportingIndexTest` verifies:

- explicit identity with no PK gets a supporting KEY;
- identity not leading a composite PK gets a supporting KEY;
- identity already leading the PK does not get a redundant supporting KEY.
