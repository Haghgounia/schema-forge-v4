package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import java.util.List;
import java.util.Objects;
public record PrimaryKey(Identifier name, List<Identifier> columns) {
    public PrimaryKey { columns = List.copyOf(Objects.requireNonNull(columns)); if (columns.isEmpty()) throw new IllegalArgumentException("primary key must contain columns"); }
}
