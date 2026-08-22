package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;
import java.util.Optional;

/** One deterministic table-object difference between live metadata and the desired document model. */
public record TableObjectChange(
        TableObjectType objectType,
        TableObjectChangeKind kind,
        Identifier objectName,
        Object before,
        Object after,
        MigrationRisk risk,
        String rationale) {

    public TableObjectChange {
        Objects.requireNonNull(objectType, "objectType must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(risk, "risk must not be null");
        rationale = rationale == null ? "" : rationale.trim();
        if (before == null && after == null) {
            throw new IllegalArgumentException("table object change requires before or after state");
        }
    }

    public Optional<Object> beforeState() { return Optional.ofNullable(before); }
    public Optional<Object> afterState() { return Optional.ofNullable(after); }
}
