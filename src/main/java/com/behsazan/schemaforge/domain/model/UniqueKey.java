package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import java.util.List;
import java.util.Objects;
public record UniqueKey(Identifier name, List<Identifier> columns) {
    public UniqueKey { columns = List.copyOf(Objects.requireNonNull(columns)); if (columns.isEmpty()) throw new IllegalArgumentException("unique key must contain columns"); }
}
