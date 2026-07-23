package com.behsazan.schemaforge.model;

import java.util.List;

public record TableDefinition(
        String schema,
        String name,
        List<ColumnDefinition> columns
) {
    public TableDefinition {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
