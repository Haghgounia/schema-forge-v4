package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;
import java.util.Optional;

/** One deterministic column difference between live metadata and the desired document model. */
public record ColumnChange(
        ColumnChangeKind kind,
        Identifier columnName,
        Column before,
        Column after,
        MigrationRisk risk,
        String rationale) {

    public ColumnChange {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(columnName, "columnName must not be null");
        Objects.requireNonNull(risk, "risk must not be null");
        rationale = rationale == null ? "" : rationale.trim();
        if (before == null && after == null) {
            throw new IllegalArgumentException("column change requires before or after state");
        }
    }

    public Optional<Column> beforeState() { return Optional.ofNullable(before); }
    public Optional<Column> afterState() { return Optional.ofNullable(after); }
}
