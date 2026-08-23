# SchemaForge V4 - C5.3-R2 MySQL NUMBER(19) AUTO_INCREMENT compatibility repair

Status: **REPAIR CANDIDATE / PENDING MAVEN REGRESSION**
Official baseline remains: `SCHEMAFORGE-V4-CONSOLIDATED-BASELINE-20260822-C5.3`

## 1. Trigger

A real Enterprise Architect REST input exposed this MySQL generation error:

```text
MySQL AUTO_INCREMENT requires an integer column; no lossless integer mapping exists for
DataType[name=NUMBER, precision=19, scale=0] on PARTY_CLASSIFICATION_ID
```

The representative EA fixture already present in `src/test/resources/Party_14050514.xml` contains
`NUMBER(19,0)` columns marked `AutoNum=True`, including `PARTY_CLASSIFICATION_ID`.

This is not a C5 naming/layout regression. It is a pre-existing cross-dialect portability boundary
that became visible when the EA corpus exercised MySQL generation.

## 2. Repair policy

C5.3-R2 applies the following evidence-bounded MySQL policy:

1. exact `NUMBER/NUMERIC/DECIMAL/DEC(19,0)` identity columns map to
   `BIGINT UNSIGNED AUTO_INCREMENT`;
2. exact `NUMBER(19,0)` child columns are mapped to `BIGINT UNSIGNED` only when the canonical
   schema proves that the FK reaches an internally modeled `NUMBER(19,0)` identity key;
3. propagation follows actual canonical FK column mappings and can cross internal FK chains;
4. ordinary `NUMBER(19,0)` values remain `DECIMAL(19)`;
5. a referenced parent outside the supplied schema context is not guessed and therefore does not
   trigger unsigned adaptation;
6. generated SQL carries an inline SchemaForge portability comment for both identity and FK-side
   adaptations;
7. no canonical datatype is mutated and no parser metadata is rewritten.

MySQL 8.4 requires `AUTO_INCREMENT` to use an integer type. `BIGINT UNSIGNED` covers the complete
nonnegative 19-digit decimal range. MySQL does not support negative `AUTO_INCREMENT` values, so the
DDL comment explicitly instructs review of pre-existing negative source values before migration.

## 3. Implementation scope

Production source changed:

- `dialect/Dialect.java` - additive schema-aware datatype/adaptation hooks with backward-compatible defaults;
- `dialect/mysql/MySqlDialect.java` - NUMBER(19) identity mapping and FK propagation;
- `generation/DdlGenerator.java` - optional full-schema type-mapping context for per-table rendering;
- `metadata/validation/MetadataComparisonValidator.java` - use schema-aware desired type rendering;
- `api/SchemaForgeApiService.java` - EA per-table DDL supplies the full canonical schema as type context.

Tests changed/added:

- `MySqlDialectFoundationTest`;
- `MySqlDdlGeneratorTest`;
- `MySqlEnterpriseArchitectIdentityCompatibilityTest` (new, uses the real Party EA fixture).

## 4. Explicit non-goals

R2 does not change:

- EA parsing or `AutoNum` recognition;
- canonical model semantics;
- Oracle/PostgreSQL/Db2/SQL Server type mappings;
- C5 artifact naming or layout;
- REST endpoint URLs, request parameters, response bodies, or error contract;
- migration diff policy;
- C6 manifest design.

## 5. Verification performed in the build environment

Maven could not run in the build environment because the Maven wrapper download from Maven Central
was unavailable. The following local verification succeeded:

```text
Java 21 compile of Dialect/MySqlDialect/DdlGenerator/MetadataComparisonValidator/EA parser : PASS
Real Party_14050514.xml per-table MySQL NUMBER(19) compatibility probe                  : PASS
```

The real fixture probe verifies both:

```text
PARTY_CLASSIFICATION_ID -> BIGINT UNSIGNED AUTO_INCREMENT
PARTY_ID                -> BIGINT UNSIGNED (internal FK compatibility propagation)
```

## 6. Separate known limitation discovered during the probe

After the NUMBER(19) blocker is removed, a full-schema MySQL generation probe reaches a separate,
older boundary: the same EA corpus contains `TIMESTAMP WITH TIME ZONE`, for which the current MySQL
dialect deliberately has no lossless mapping. That limitation is recorded in
`docs/reference/KNOWN-LIMITATIONS.md` and is not folded into R2 because it has different semantics
and the REST path is not currently urgent.

## 7. Regression gate

Targeted Maven regression must pass before R2 can be promoted. A full clean regression is then
required before any C6 production-source implementation is layered on top of this repair.

Expected source inventory for the R2 candidate:

```text
Main Java files : 253
Test Java files : 173
Expected full Surefire tests : 496
```
