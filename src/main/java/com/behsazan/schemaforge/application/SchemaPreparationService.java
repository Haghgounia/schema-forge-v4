package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.specification.validation.spelling.LanguageToolSpellCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.enrichment.AuditColumnSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.GrantSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.SchemaEnricher;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;

import java.util.List;
import java.util.Objects;

/**
 * Single DBMS-independent preparation pipeline.
 * Every output dialect must receive the same prepared canonical model.
 */
public final class SchemaPreparationService {
    private final SpecificationNormalizer normalizer;
    private final List<SchemaEnricher> enrichers;
    private final SpecificationValidator validator;

    public SchemaPreparationService() {
        this(AuditProperties.defaults(), GrantProperties.defaults(), SpellCheckProperties.defaults(), new ObjectMapper());
    }

    public SchemaPreparationService(AuditProperties auditProperties) {
        this(auditProperties, GrantProperties.defaults(), SpellCheckProperties.defaults(), new ObjectMapper());
    }

    public SchemaPreparationService(
            AuditProperties auditProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper) {
        this(auditProperties, GrantProperties.defaults(), spellCheckProperties, objectMapper);
    }

    public SchemaPreparationService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper) {
        this(
                new SpecificationNormalizer(),
                List.of(
                        new AuditColumnSchemaEnricher(auditProperties),
                        new GrantSchemaEnricher(grantProperties)),
                new SpecificationValidator(new LanguageToolSpellCheckService(spellCheckProperties, objectMapper))
        );
    }

    public SchemaPreparationService(
            SpecificationNormalizer normalizer,
            List<SchemaEnricher> enrichers,
            SpecificationValidator validator) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema) {
        DatabaseSchema current = normalizer.normalize(
                Objects.requireNonNull(parsedSchema, "parsedSchema must not be null"));
        for (SchemaEnricher enricher : enrichers) {
            current = Objects.requireNonNull(enricher.enrich(current),
                    "schema enricher returned null: " + enricher.getClass().getName());
        }
        return new PreparedSchema(current, validator.validate(current));
    }
}
