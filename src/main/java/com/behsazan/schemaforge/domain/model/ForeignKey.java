package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.List;
import java.util.Objects;
public record ForeignKey(Identifier name, List<Identifier> columns, QualifiedName referencedTable,
                         List<Identifier> referencedColumns, ReferentialAction onDelete, ReferentialAction onUpdate) {
    public ForeignKey {
        columns = List.copyOf(Objects.requireNonNull(columns));
        referencedColumns = List.copyOf(Objects.requireNonNull(referencedColumns));
        Objects.requireNonNull(referencedTable);
        if (columns.isEmpty() || columns.size() != referencedColumns.size()) throw new IllegalArgumentException("foreign key column counts must be equal and non-empty");
        onDelete = onDelete == null ? ReferentialAction.NO_ACTION : onDelete;
        onUpdate = onUpdate == null ? ReferentialAction.NO_ACTION : onUpdate;
    }
}
