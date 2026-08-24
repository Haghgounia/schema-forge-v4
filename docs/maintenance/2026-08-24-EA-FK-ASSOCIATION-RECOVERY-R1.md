# EA FK Association Recovery R1

Date: 2026-08-24
Status: verification candidate
Baseline: SchemaForge V4 C11 official/recovered baseline

## Evidence

Real-input acceptance file: `FEE-DataModel-EA-XMI-1.1.xmi`.

The file contains:

- 21 table classes
- 28 FK operations
- 28 UML associations with stereotype `FK`
- association tagged values: `constraint`, `sourceTable`, `sourceColumns`, `targetTable`, `targetColumns`
- association-end tagged values: `role=child` and `role=parent`

The pre-patch parser only recognized native EA association metadata based on:

- `styleex=FKINFO=SRC=...:DST=...:`
- association-end `ea_end=source|target`
- textual column mappings such as `A = B`

As a result, all 28 real FK operations emitted `EA_FK_ASSOCIATION_NOT_FOUND` and the canonical model contained zero foreign keys.

## Change

`EnterpriseArchitectXmlParser` now retains the existing EA-native behavior and adds fallbacks for portable/generated XMI 1.1 exports:

1. FK operation name may be resolved from association tag `constraint` or the association name when `styleex/FKINFO` is absent.
2. `role=child` is treated as the FK/source end when `ea_end` is absent.
3. `role=parent` is treated as the referenced/target end when `ea_end` is absent.
4. `sourceColumns` and `targetColumns` are used as ordered column pairs when textual `A = B` mappings are absent.
5. `sourceTable` and `targetTable` are retained as table-name fallbacks.

## Compatibility

The original `styleex/FKINFO + ea_end=source|target` path is unchanged and remains the first-choice path.

## Verification evidence available before Maven execution

The patched parser was compiled directly with Java 21. A probe against the real acceptance XMI resolved:

- Tables: 21
- PK: 21
- FK: 28
- UK: 14
- Indexes: 10
- Checks: 7
- Recovery warnings: 0

The existing repository sample `docs/samples/ea/ea-sample.xml` also continued to resolve its existing FK with zero recovery warnings.

## Required Maven verification

Run:

```bat
mvnw.cmd -Dtest=EnterpriseArchitectXmlParserTest,SchemaForgeEaPerTableOutputTest,MySqlEnterpriseArchitectIdentityCompatibilityTest test
```

Then rerun the real EA REST acceptance and verify `recovery.warningCount=0` and 28 foreign keys in the canonical schema/output DDL.
