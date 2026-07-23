package com.behsazan.schemaforge.model;

public record ColumnDefinition(
        String name,
        String logicalType,
        Integer length,
        Integer precision,
        Integer scale,
        boolean nullable,
        String defaultValue
) {
}
