package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Objects;

/**
 * Represents a database-independent primary key in the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record PrimaryKey(Identifier name, List<Identifier> columns, boolean deferrable, boolean initiallyDeferred) {
    public PrimaryKey {
        columns = List.copyOf(Objects.requireNonNull(columns));
        if (columns.isEmpty()) throw new IllegalArgumentException("primary key must contain columns");
        if (initiallyDeferred && !deferrable) {
            throw new IllegalArgumentException("initially deferred primary key must be deferrable");
        }
    }

    public PrimaryKey(Identifier name, List<Identifier> columns) {
        this(name, columns, false, false);
    }
}
