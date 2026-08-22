# ALTER / Flyway Migration M2

M2 extends the M1 column diff with table-owned structural objects while preserving the existing CREATE path.

## Output contract

- CREATE DDL is always generated exactly as before, even when the table already exists in the target database.
- When live metadata exists and differs from the desired canonical table, an additional Flyway-compatible versioned migration is written under `<platform>/migrations/`.
- No migration file is emitted when no live-to-desired difference exists.

## M2 structural coverage

M2 compares and renders changes for:

- primary keys
- foreign keys owned by the current table
- unique keys
- check constraints
- standalone indexes
- all M1 column changes

A changed named object is represented as a deterministic DROP + ADD replacement. Structural drops that remove integrity constraints are classified DESTRUCTIVE and are commented unless `confirmDestructive=true`. Standalone index drops are REVIEW because they affect performance/physical access but not stored row data.

## Ordering

1. DROP/REPLACE phase for table-owned constraints and indexes
2. M1 column changes
3. ADD/REPLACE phase for table-owned constraints and indexes

This ordering prevents many local dependency errors when a changed object must be replaced around a column alteration. Incoming foreign keys owned by other tables are deliberately not guessed or auto-dropped; deployment-wide dependency planning is still required before changing a referenced key/column.

## MySQL live metadata

M2 expands `JdbcMySqlMetadataRepository` to load primary/unique constraints, foreign keys, checks and standalone indexes from `information_schema`. The MySQL catalog name `PRIMARY` is treated as equivalent to an explicitly named canonical primary key when the key columns are identical; this avoids a false replacement on every existing MySQL primary key.

## No-guess rules

- column rename is never inferred
- ambiguous object names are not synthesized for DROP
- physical index option drift is not auto-rebuilt in M2
- incoming/cross-table dependencies are not auto-dropped
- destructive object replacements stay commented until explicitly confirmed

Physical-option migration and deployment-wide dependency orchestration remain later work.

## Real MySQL execution pilot

The opt-in `MySqlMigrationM2LivePilotIT` validates this contract on a disposable `SCHEMAFORGE_*` MySQL database.
It generates CREATE SQL while the old table already exists, writes both safe and confirmed Flyway-compatible
migration artifacts, executes the confirmed migration, reloads `information_schema`, requires an empty residual
diff, and verifies seed-row preservation. See `ALTER-MIGRATION-M2-LIVE-PILOT.md`.
