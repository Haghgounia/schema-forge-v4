package com.behsazan.schemaforge.model;

import java.util.List;

public record SchemaDefinition(
        String name,
        List<TableDefinition> tables
) {
    public SchemaDefinition {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
