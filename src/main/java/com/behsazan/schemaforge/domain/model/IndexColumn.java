package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import java.util.Objects;
public record IndexColumn(Identifier column, SortDirection direction) {
    public IndexColumn { Objects.requireNonNull(column); direction = direction == null ? SortDirection.ASC : direction; }
}
