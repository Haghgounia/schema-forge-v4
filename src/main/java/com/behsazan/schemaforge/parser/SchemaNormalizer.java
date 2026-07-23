package com.behsazan.schemaforge.parser;

import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.SchemaDefinition;
import com.behsazan.schemaforge.model.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SchemaNormalizer {

    public SchemaDefinition normalize(SchemaDefinition source) {
        if (source == null) {
            throw new IllegalArgumentException("Schema definition must not be null");
        }

        List<TableDefinition> tables = source.tables().stream()
                .map(this::normalizeTable)
                .toList();

        return new SchemaDefinition(clean(source.name()), tables);
    }

    private TableDefinition normalizeTable(TableDefinition table) {
        List<ColumnDefinition> columns = table.columns().stream()
                .map(this::normalizeColumn)
                .toList();
        return new TableDefinition(clean(table.schema()), clean(table.name()), columns);
    }

    private ColumnDefinition normalizeColumn(ColumnDefinition column) {
        return new ColumnDefinition(
                clean(column.name()),
                upper(column.logicalType()),
                column.length(),
                column.precision(),
                column.scale(),
                column.nullable(),
                clean(column.defaultValue())
        );
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    private String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase();
    }
}
