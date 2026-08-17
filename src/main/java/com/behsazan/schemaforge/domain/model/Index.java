package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical index model including PostgreSQL INCLUDE/partial-index semantics and
 * optional object-scoped physical options and index-build directives.
 *
 * <p>Index physical options are deliberately additive and optional. Existing
 * table-scoped physical options remain supported as a backward-compatible
 * fallback in Physical Phase 1. Build options are kept separate because ONLINE,
 * CONCURRENTLY, RESUMABLE and similar directives describe the creation operation,
 * not the persistent physical state of the index.</p>
 */
public record Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description,
                    List<Identifier> includeColumns, String predicate, Map<String, String> physicalOptions,
                    Map<String, String> buildOptions) {
    public Index {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        if (columns.isEmpty()) throw new IllegalArgumentException("index must contain columns");
        type = type == null ? IndexType.NORMAL : type;
        description = description == null ? Description.empty() : description;
        includeColumns = includeColumns == null ? List.of() : List.copyOf(includeColumns);
        predicate = normalize(predicate);
        physicalOptions = physicalOptions == null ? Map.of() : Map.copyOf(physicalOptions);
        buildOptions = buildOptions == null ? Map.of() : Map.copyOf(buildOptions);
    }

    /** Backward-compatible constructor for pre-P5 callers that only carry physical options. */
    public Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description,
                 List<Identifier> includeColumns, String predicate, Map<String, String> physicalOptions) {
        this(name, columns, type, description, includeColumns, predicate, physicalOptions, Map.of());
    }

    public Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description,
                 List<Identifier> includeColumns, String predicate) {
        this(name, columns, type, description, includeColumns, predicate, Map.of(), Map.of());
    }

    public Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description) {
        this(name, columns, type, description, List.of(), null, Map.of(), Map.of());
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
