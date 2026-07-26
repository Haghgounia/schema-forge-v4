package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Objects;

/** Canonical index model including PostgreSQL INCLUDE and partial-index predicate. */
public record Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description,
                    List<Identifier> includeColumns, String predicate) {
    public Index {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        if (columns.isEmpty()) throw new IllegalArgumentException("index must contain columns");
        type = type == null ? IndexType.NORMAL : type;
        description = description == null ? Description.empty() : description;
        includeColumns = includeColumns == null ? List.of() : List.copyOf(includeColumns);
        predicate = normalize(predicate);
    }

    public Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description) {
        this(name, columns, type, description, List.of(), null);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.endsWith(";")) {
            throw new IllegalArgumentException("index predicate must not end with a semicolon");
        }
        return normalized;
    }
}
