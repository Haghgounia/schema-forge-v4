package com.behsazan.schemaforge.generation;

import com.behsazan.schemaforge.dialect.DatabaseDialect;
import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.SchemaDefinition;
import com.behsazan.schemaforge.model.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class GenerationEngine {
    public String generateDdl(SchemaDefinition schema, DatabaseDialect dialect) {
        return schema.tables().stream()
                .map(table -> generateTable(table, dialect))
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
    }

    private String generateTable(TableDefinition table, DatabaseDialect dialect) {
        String tableName = qualifiedName(table, dialect);
        String columns = table.columns().stream()
                .map(column -> generateColumn(column, dialect))
                .collect(Collectors.joining("," + System.lineSeparator()));
        return "CREATE TABLE " + tableName + " (" + System.lineSeparator()
                + columns + System.lineSeparator() + ");";
    }

    private String generateColumn(ColumnDefinition column, DatabaseDialect dialect) {
        String name = dialect.quoteIdentifier(dialect.normalizeIdentifier(column.name()));
        String nullable = column.nullable() ? "" : " NOT NULL";
        String defaultValue = column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue();
        return "    " + name + " " + dialect.sqlType(column) + defaultValue + nullable;
    }

    private String qualifiedName(TableDefinition table, DatabaseDialect dialect) {
        String name = dialect.quoteIdentifier(dialect.normalizeIdentifier(table.name()));
        if (table.schema() == null) {
            return name;
        }
        return dialect.quoteIdentifier(dialect.normalizeIdentifier(table.schema())) + "." + name;
    }
}
