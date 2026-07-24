package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural validation only; it never connects to a database. */
public final class SpecificationValidator {
    public ValidationReport validate(DatabaseSchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (schema.tables().isEmpty()) {
            issues.add(error("SCHEMA_NO_TABLE", "schema", "No table was extracted from the Word file."));
        }
        for (Table table : schema.tables()) validateTable(table, issues);
        return new ValidationReport(issues.stream().noneMatch(i -> "ERROR".equals(i.severity())), issues);
    }

    private void validateTable(Table table, List<ValidationIssue> issues) {
        String tablePath = "tables." + table.qualifiedName().name().value();
        if (table.columns().isEmpty()) {
            issues.add(error("TABLE_NO_COLUMN", tablePath, "Table has no columns."));
            return;
        }
        Set<String> names = new HashSet<>();
        for (Column column : table.columns()) {
            String name = column.name().normalized();
            String path = tablePath + ".columns." + column.name().value();
            if (!names.add(name)) issues.add(error("DUPLICATE_COLUMN", path, "Duplicate column name."));
        }
        table.primaryKey().ifPresent(pk -> {
            if (pk.columns().isEmpty()) issues.add(error("PRIMARY_KEY_EMPTY", tablePath + ".primaryKey", "Primary key has no columns."));
        });
    }

    private static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue("ERROR", code, path, message);
    }
}
