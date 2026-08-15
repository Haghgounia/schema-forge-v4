# V4 Mermaid + Graphviz Validation Evidence

## Regression

- Maven tests: 313
- Failures: 0
- Errors: 0
- Skipped: 3
- Build: SUCCESS

## Word regression

- Documents: 9
- Passed: 9
- Failed: 0
- Tables: 9
- Columns: 117

## SchemaDocuments_3 integration smoke test

- DOCX: 75
- Successful: 75
- Oracle SQL: 75
- PostgreSQL SQL: 75
- SQL Server SQL: 75
- Db2 z/OS SQL: 75
- Canonical JSON: 75
- Mermaid per-table ER: 75
- Graphviz per-table DOT: 75

## Mermaid batch

- Batch ER: PASS
- Batch dependency: PASS
- Duplicate protection: PASS
- Self-reference rendering: PASS
- Unresolved FK reporting: PASS

## Graphviz batch

- Flat dependency DOT: PASS
- Full clustered DOT: PASS
- Compact DOT: PASS
- Overview DOT: PASS
- Schema clustering: PASS
- FK preservation: PASS
- FK-label suppression: PASS
- Disconnected-table filtering: PASS

## Relationship metrics

- Table definitions: 75
- Distinct table names: 72
- Duplicate table names: 3
- Exported unique tables: 69
- Connected tables: 43
- Physical FKs: 58
- Resolved physical FKs: 48
- Issues: 13

## Runtime policy

Graphviz output is DOT-only. SchemaForge does not invoke or require `dot.exe` or another Graphviz binary at runtime.
