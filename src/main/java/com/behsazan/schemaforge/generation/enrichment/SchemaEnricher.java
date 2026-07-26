package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

@FunctionalInterface
public interface SchemaEnricher {
    DatabaseSchema enrich(DatabaseSchema schema);
}
