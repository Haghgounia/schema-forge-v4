package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Objects;

public record UniqueKey(Identifier name, List<Identifier> columns, boolean deferrable, boolean initiallyDeferred) {
    public UniqueKey {
        columns = List.copyOf(Objects.requireNonNull(columns));
        if (columns.isEmpty()) throw new IllegalArgumentException("unique key must contain columns");
        if (initiallyDeferred && !deferrable) {
            throw new IllegalArgumentException("initially deferred unique key must be deferrable");
        }
    }

    public UniqueKey(Identifier name, List<Identifier> columns) {
        this(name, columns, false, false);
    }
}
