package com.behsazan.schemaforge.specification.json;

import com.behsazan.schemaforge.domain.model.*;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.issue.SqlIssueCatalog;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the phase-1 canonical schema and validation report as readable JSON. */
public final class JsonExporter {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path output, DatabaseSchema schema, ValidationReport validation) throws IOException {
        List<ValidationIssue> issues =
                SqlIssueCatalog.from(schema, validation).all();
        ValidationReport completeValidation = new ValidationReport(
                issues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                issues);
        mapper.writeValue(output.toFile(), document(schema, completeValidation));
    }

    private Map<String, Object> document(DatabaseSchema schema, ValidationReport validation) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", schema.metadata());
        root.put("schema", schema(schema));
        root.put("validation", validation);
        return root;
    }

    private Map<String, Object> schema(DatabaseSchema schema) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", schema.name().value());
        value.put("description", schema.description().value());
        value.put("tables", schema.tables().stream().map(this::table).toList());
        value.put("sequences", schema.sequences().stream().map(this::sequence).toList());
        return value;
    }

    private Map<String, Object> table(Table table) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", table.qualifiedName().schemaName().map(Identifier::value).orElse(null));
        value.put("name", table.qualifiedName().name().value());
        value.put("description", table.description().value());
        value.put("columns", table.columns().stream().map(this::column).toList());
        value.put("primaryKey", table.primaryKey().map(this::primaryKey).orElse(null));
        value.put("uniqueKeys", table.uniqueKeys().stream().map(this::uniqueKey).toList());
        value.put("foreignKeys", table.foreignKeys().stream().map(this::foreignKey).toList());
        value.put("checkConstraints", table.checkConstraints().stream().map(this::check).toList());
        value.put("indexes", table.indexes().stream().map(this::index).toList());
        return value;
    }

    private Map<String, Object> column(Column column) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", column.name().value());
        value.put("logicalType", column.dataType().name().value());
        value.put("length", column.dataType().length());
        value.put("lengthSemantics", column.dataType().lengthSemantics().name());
        value.put("precision", column.dataType().precision());
        value.put("scale", column.dataType().scale());
        value.put("nullable", column.nullable());
        value.put("defaultValue", column.defaultValue().expression());
        value.put("description", column.description().value());
        value.put("identity", column.identity());
        value.put("ordinalPosition", column.ordinalPosition());
        value.put("generatedExpression", column.generatedExpression());
        return value;
    }

    private Map<String, Object> primaryKey(PrimaryKey key) {
        return namedColumns(key.name(), key.columns());
    }

    private Map<String, Object> uniqueKey(UniqueKey key) {
        return namedColumns(key.name(), key.columns());
    }

    private Map<String, Object> namedColumns(Identifier name, List<Identifier> columns) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name == null ? null : name.value());
        value.put("columns", columns.stream().map(Identifier::value).toList());
        return value;
    }

    private Map<String, Object> foreignKey(ForeignKey key) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", key.name() == null ? null : key.name().value());
        value.put("columns", key.columns().stream().map(Identifier::value).toList());
        value.put("referencedTable", key.referencedTable().toString());
        value.put("referencedColumns", key.referencedColumns().stream().map(Identifier::value).toList());
        value.put("onDelete", key.onDelete().name());
        value.put("onUpdate", key.onUpdate().name());
        value.put("physicalReference", key.physicalReference());
        value.put("schemaExplicit", key.schemaExplicit());
        return value;
    }

    private Map<String, Object> check(CheckConstraint check) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", check.name() == null ? null : check.name().value());
        value.put("expression", check.expression());
        return value;
    }

    private Map<String, Object> index(Index index) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", index.name() == null ? null : index.name().value());
        value.put("type", index.type().name());
        value.put("columns", index.columns().stream().map(c -> Map.of(
                "name", c.column().value(),
                "direction", c.direction().name())).toList());
        return value;
    }

    private Map<String, Object> sequence(Sequence sequence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", sequence.qualifiedName().toString());
        value.put("startWith", sequence.startWith());
        value.put("incrementBy", sequence.incrementBy());
        value.put("minValue", sequence.minValue());
        value.put("maxValue", sequence.maxValue());
        value.put("cycle", sequence.cycle());
        value.put("cacheSize", sequence.cacheSize());
        return value;
    }
}
