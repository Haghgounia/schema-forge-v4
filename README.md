# SchemaForge - Phase 1

Phase 1 has exactly one responsibility:

```text
Word (.docx) -> Parse -> Normalize -> Validate offline -> JSON
```

## Entry point

`com.behsazan.schemaforge.Phase1Application`

## Build

```bash
mvn clean package
```

## Run

```bash
java -jar target/schema-forge-phase1.jar input.docx output.json
```

The output argument is optional. Without it, JSON is created next to the Word file.

## Scope

Included:
- Word table specification parser
- Canonical schema/table/column model
- Field normalization
- Structural validation without database access
- JSON export

Excluded from Phase 1:
- Enterprise Architect input
- DDL generation
- Oracle/PostgreSQL dialects
- JDBC and repositories
- REST API and Spring Boot
- Database-backed validation
