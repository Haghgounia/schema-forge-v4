package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Objects;

/** Resolved physical foreign key scheduled for integrated phase-3 deployment. */
public record ForeignKeyDeployment(
        QualifiedName table,
        ForeignKey foreignKey,
        QualifiedName referencedTable) {

    public ForeignKeyDeployment {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(foreignKey, "foreignKey must not be null");
        Objects.requireNonNull(referencedTable, "referencedTable must not be null");
        if (!foreignKey.physicalReference()) {
            throw new IllegalArgumentException("Only physical foreign keys can be deployed");
        }
    }
}
