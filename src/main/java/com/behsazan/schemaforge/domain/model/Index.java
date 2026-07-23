package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.List;
import java.util.Objects;
public record Index(Identifier name, List<IndexColumn> columns, IndexType type, Description description) {
    public Index { columns = List.copyOf(Objects.requireNonNull(columns)); if (columns.isEmpty()) throw new IllegalArgumentException("index must contain columns"); type = type == null ? IndexType.NORMAL : type; description = description == null ? Description.empty() : description; }
}
