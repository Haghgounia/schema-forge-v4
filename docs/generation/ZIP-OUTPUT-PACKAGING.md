# ZIP Output Packaging

SchemaForge ZIP batch generation keeps artifact generation unchanged and organizes the returned archive by artifact type.

## Layout

```text
output/
├── oracle/
├── postgresql/
├── sqlserver/
├── db2zos/
├── excel/
├── json/
├── mermaid/
│   ├── tables/
│   └── batch/
│       ├── schema-er.mmd
│       ├── schema-dependency.mmd
│       ├── issues.csv
│       └── summary.txt
└── reports/
    ├── batch-generation-summary.csv
    ├── batch-generation-errors.log
    └── *.metadata-crud-summary.csv
```

Platform-specific CRUD artifacts remain under their platform directory, for example `oracle/crud/` and `sqlserver/crud/`.

This layout applies to `SchemaForgeApiService.generateFromZip(...)`. Single-document Word and legacy Word endpoints keep their existing archive layout.

No parser, canonical model, DDL renderer, Mermaid renderer, or metadata comparison behavior is changed by this packaging step.
