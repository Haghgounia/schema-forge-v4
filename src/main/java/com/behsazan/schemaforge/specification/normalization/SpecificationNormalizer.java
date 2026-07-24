package com.behsazan.schemaforge.specification.normalization;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

import java.util.Objects;

/** Explicit phase-1 normalization stage. The Word parser already applies field-level normalization. */
public final class SpecificationNormalizer {
    public DatabaseSchema normalize(DatabaseSchema schema) {
        return Objects.requireNonNull(schema, "schema must not be null");
    }
}
