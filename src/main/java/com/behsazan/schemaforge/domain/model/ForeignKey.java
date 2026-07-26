package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.List;
import java.util.Objects;

/**
 * Represents a database-independent foreign key in the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record ForeignKey(Identifier name, List<Identifier> columns, QualifiedName referencedTable,
                         List<Identifier> referencedColumns, ReferentialAction onDelete, ReferentialAction onUpdate,
                         boolean deferrable, boolean initiallyDeferred,
                         boolean physicalReference, boolean schemaExplicit) {
    public ForeignKey {
        columns = List.copyOf(Objects.requireNonNull(columns));
        referencedColumns = List.copyOf(Objects.requireNonNull(referencedColumns));
        Objects.requireNonNull(referencedTable);
        if (columns.isEmpty() || columns.size() != referencedColumns.size()) {
            throw new IllegalArgumentException("foreign key column counts must be equal and non-empty");
        }
        onDelete = onDelete == null ? ReferentialAction.NO_ACTION : onDelete;
        onUpdate = onUpdate == null ? ReferentialAction.NO_ACTION : onUpdate;
        if (initiallyDeferred && !deferrable) {
            throw new IllegalArgumentException("initially deferred foreign key must be deferrable");
        }
    }

    public ForeignKey(Identifier name, List<Identifier> columns, QualifiedName referencedTable,
                      List<Identifier> referencedColumns, ReferentialAction onDelete, ReferentialAction onUpdate) {
        this(name, columns, referencedTable, referencedColumns, onDelete, onUpdate, false, false, true, false);
    }

    public ForeignKey(Identifier name, List<Identifier> columns, QualifiedName referencedTable,
                      List<Identifier> referencedColumns, ReferentialAction onDelete, ReferentialAction onUpdate,
                      boolean deferrable, boolean initiallyDeferred) {
        this(name, columns, referencedTable, referencedColumns, onDelete, onUpdate, deferrable, initiallyDeferred,
                true, false);
    }
}
