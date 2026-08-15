# SchemaForge V4 Validation Evidence

## Regression evidence

Latest full regression supplied during finalization:

```text
Tests run: 270, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

The three skipped tests are the database execution ITs that use JUnit assumptions in the default build and are executed explicitly when database configuration is supplied.

## Historical execution evidence

### Oracle

```text
Main historical run:
Files discovered    : 4766
Statements executed : 115804
Statements succeeded: 115804
Statements failed   : 0
Cleanup succeeded   : 4766

Collision coverage run:
Files discovered    : 4
Statements executed : 86
Statements succeeded: 86
Statements failed   : 0
```

### PostgreSQL

```text
Files discovered     : 4768
Statements executed  : 120614
Statements succeeded : 120614
Statements failed    : 0
Actionable failures  : 0
Cleanup succeeded    : 4768
Execution mode       : HISTORICAL
```

### SQL Server

```text
Full run:
Files discovered     : 4768
Statements succeeded : 130150
Runner-only FK check errors identified: 1285 in 518 files

Corrected retry:
Files discovered     : 518
Statements executed  : 17491
Statements succeeded : 17491
Statements failed    : 0
Actionable failures  : 0
Cleanup succeeded    : 518
```

## Integrated large-pilot evidence

Pilot definition:

```text
Pilot tables           : 15
Physical FKs           : 13
Resolved physical FKs  : 13
FK chain depth         : 2
Connected components   : 3
FK blockers            : 0
```

Oracle:

```text
Statements executed : 260
Statements succeeded: 260
Statements failed   : 0
Cleanup succeeded   : 15
Execution mode      : FULL
```

PostgreSQL:

```text
Statements executed : 261
Errors              : 0
Execution mode      : FULL
```

SQL Server:

```text
Statements executed  : 274
Statements succeeded : 274
Statements failed    : 0
FK cleanup succeeded : 13
Cleanup succeeded    : 15
Execution mode       : FULL
```

## Dependency coverage evidence

```text
Snapshots discovered              : 4768
Snapshots loaded                  : 4768
Snapshot failures                 : 0
Distinct table names              : 2391
Duplicate occurrences             : 2377
Foreign-key definitions           : 1285
Physical FK definitions           : 1285
Logical FK definitions            : 0
Distinct physical FK relations    : 605
Aggregate dependency edges        : 317
Missing target definitions        : 527
Self-reference definitions        : 25
Distinct self-reference relations : 5
Aggregate cycle candidate groups  : 2
Tables in aggregate cycles        : 4
```

Cycle classification:

```text
CTACCOUNTS <-> MSCUSTOMERS
  HISTORICAL_AGGREGATE_ONLY

CTPLICENSEDUPNID <-> JTDTOCUSTOMERS
  HISTORICAL_AGGREGATE_ONLY
```

No one-version-per-table compatible selection preserves either cycle.
