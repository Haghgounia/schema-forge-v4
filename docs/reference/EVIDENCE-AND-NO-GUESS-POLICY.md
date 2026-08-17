# Evidence and No-Guess Policy

## 1. Principle

SchemaForge must preserve uncertainty instead of converting incomplete evidence into a fabricated schema fact.

This policy applies to parsing, datatype mapping, physical DDL, metadata comparison, and reverse engineering.

## 2. Accepted evidence categories

Depending on the workflow, a value may be supported by:

- explicit Word/legacy Word cells;
- explicit EA input;
- canonical JSON/snapshot data with compatible provenance;
- approved configuration/profile values;
- explicit project conventions already encoded in the current baseline;
- live database catalog metadata for the **actual/comparison** side;
- explicit build options supplied to the index model.

Evidence on one side does not automatically become evidence on another side. In particular, current database metadata is not design intent.

## 3. Parser and datatype rules

SchemaForge does not silently invent:

- table names;
- column names;
- datatypes;
- character length;
- numeric precision;
- numeric scale;
- default values when source text cannot be reduced safely.

Ambiguous legacy rows remain unresolved findings. Invalid combinations such as scale greater than precision are not repaired by guessing.

## 4. Physical design rules

SchemaForge does not silently invent environment/workload-specific physical settings such as:

- arbitrary tablespaces/filegroups/stogroups;
- storage allocation quantities;
- compression choices;
- partitioning strategy;
- LOB placement;
- access-method choices;
- recovery policies;
- workload-dependent build options.

A project convention explicitly encoded and already frozen in the application is a configured policy, not a metadata inference. Such conventions must remain documented and must not be confused with source-derived evidence.

## 5. Invalid physical source values

Where a renderer can validate a physical source value:

- valid explicit values may be retained;
- invalid values are not clamped silently;
- invalid/inapplicable values remain visible as issue/review text;
- executable DDL is omitted when safe rendering cannot be proved.

## 6. Database metadata rules

Current database state is used to answer:

```text
What exists now?
```

It is not used to answer, without evidence:

```text
What DDL was originally used?
What should the design be?
What ALTER should automatically be executed?
```

Examples of prohibited reverse inference include:

- current Oracle segment existence -> original segment-creation clause;
- current Db2 allocation -> original PRIQTY/SECQTY choice;
- current index existence -> original ONLINE/CONCURRENTLY/MAXDOP build choice;
- one value from mixed SQL Server partitions -> whole-object compression truth.

## 7. `physicalOptions` versus `buildOptions`

Persistent physical state and create/rebuild operation choices are separate by design.

Database catalog acquisition may populate comparable persistent `physicalOptions` on the database-side object.

Database catalog acquisition must not infer `Index.buildOptions` unless a future explicit model proves that the catalog value is persistent and semantically identical to the modeled option.

## 8. Comparison status instead of guessing

When evidence is incomplete, the reporting layer uses statuses such as:

- `NOT_SPECIFIED`;
- `NOT_AVAILABLE`;
- `REVIEW`.

Those are intentional outcomes, not incomplete implementation failures.
