package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a database-independent unique key in the canonical schema model.
 *
 * <p>Optional physical options describe the backing/enforcing index only; they
 * do not change the logical unique-constraint semantics.</p>
 *
 * @since 4.1
 */
public record UniqueKey(Identifier name, List<Identifier> columns, boolean deferrable,
                        boolean initiallyDeferred, Map<String, String> physicalOptions) {
    public UniqueKey {
        columns = List.copyOf(Objects.requireNonNull(columns));
        if (columns.isEmpty()) throw new IllegalArgumentException("unique key must contain columns");
        if (initiallyDeferred && !deferrable) {
            throw new IllegalArgumentException("initially deferred unique key must be deferrable");
        }
        physicalOptions = physicalOptions == null ? Map.of() : Map.copyOf(physicalOptions);
    }

    public UniqueKey(Identifier name, List<Identifier> columns, boolean deferrable, boolean initiallyDeferred) {
        this(name, columns, deferrable, initiallyDeferred, Map.of());
    }

    public UniqueKey(Identifier name, List<Identifier> columns) {
        this(name, columns, false, false, Map.of());
    }
}
