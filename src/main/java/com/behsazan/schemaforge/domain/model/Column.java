package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;

public record Column(Identifier name, DataType dataType, boolean nullable, DefaultValue defaultValue,
                     Description description, boolean identity, Integer ordinalPosition,
                     String generatedExpression) {
    public Column {
        Objects.requireNonNull(name, "column name must not be null");
        Objects.requireNonNull(dataType, "column dataType must not be null");
        defaultValue = defaultValue == null ? new DefaultValue(null) : defaultValue;
        description = description == null ? Description.empty() : description;
        generatedExpression = normalizeGeneratedExpression(generatedExpression);
        if (ordinalPosition != null && ordinalPosition <= 0) {
            throw new IllegalArgumentException("ordinalPosition must be positive");
        }
        if (generatedExpression != null && identity) {
            throw new IllegalArgumentException("generated column cannot also be an identity column");
        }
        if (generatedExpression != null && defaultValue.isPresent()) {
            throw new IllegalArgumentException("generated column cannot define a default value");
        }
    }

    /** Compatibility constructor for existing callers that define a regular column. */
    public Column(Identifier name, DataType dataType, boolean nullable, DefaultValue defaultValue,
                  Description description, boolean identity, Integer ordinalPosition) {
        this(name, dataType, nullable, defaultValue, description, identity, ordinalPosition, null);
    }

    public boolean generated() {
        return generatedExpression != null;
    }

    public static Column required(String name, DataType type) {
        return new Column(Identifier.of(name), type, false, null, null, false, null, null);
    }

    public static Column nullable(String name, DataType type) {
        return new Column(Identifier.of(name), type, true, null, null, false, null, null);
    }

    private static String normalizeGeneratedExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String normalized = expression.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.endsWith(";")) {
            throw new IllegalArgumentException("generated column expression must not end with a semicolon");
        }
        return normalized;
    }
}
