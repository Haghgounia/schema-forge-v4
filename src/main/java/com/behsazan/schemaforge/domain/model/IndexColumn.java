package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;

/** A column name or scalar expression participating in an index key. */
public record IndexColumn(Identifier column, String expression, SortDirection direction) {
    public IndexColumn {
        direction = direction == null ? SortDirection.ASC : direction;
        expression = normalize(expression);
        if ((column == null) == (expression == null)) {
            throw new IllegalArgumentException("index column must define exactly one of column or expression");
        }
    }

    public IndexColumn(Identifier column, SortDirection direction) {
        this(Objects.requireNonNull(column, "column must not be null"), null, direction);
    }

    public static IndexColumn expression(String expression) {
        return new IndexColumn(null, expression, SortDirection.ASC);
    }

    public static IndexColumn expression(String expression, SortDirection direction) {
        return new IndexColumn(null, expression, direction);
    }

    public boolean expressionBased() {
        return expression != null;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.endsWith(";")) {
            throw new IllegalArgumentException("index expression must not end with a semicolon");
        }
        return normalized;
    }
}
