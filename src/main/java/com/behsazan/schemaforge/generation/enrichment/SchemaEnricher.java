package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

/**
 * Defines the contract for schema enricher implementations.
 *
 * @since 4.1
 */
@FunctionalInterface
public interface SchemaEnricher {
    DatabaseSchema enrich(DatabaseSchema schema);
}
