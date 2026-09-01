package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.enrichment.AuditColumnSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.DefaultValueSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.GrantSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.SchemaEnricher;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.spelling.LanguageToolSpellCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Single DBMS-independent preparation pipeline.
 * Every output dialect must receive the same prepared canonical model.
 */
public final class SchemaPreparationService {
    private final SpecificationNormalizer normalizer;
    private final AuditProperties auditProperties;
    private final List<SchemaEnricher> postAuditEnrichers;
    private final SpecificationValidator validator;
    private final boolean managedAuditPipeline;

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
        this.normalizer = new SpecificationNormalizer();
        this.auditProperties = Objects.requireNonNull(auditProperties, "audit properties must not be null");
        this.postAuditEnrichers = List.of(
                new DefaultValueSchemaEnricher(),
                new GrantSchemaEnricher(grantProperties));
        this.validator = new SpecificationValidator(
                new LanguageToolSpellCheckService(spellCheckProperties, objectMapper));
        this.managedAuditPipeline = true;
    }

    /** Compatibility constructor for tests/callers that supply a fully custom enrichment pipeline. */
    public SchemaPreparationService(
            SpecificationNormalizer normalizer,
            List<SchemaEnricher> enrichers,
            SpecificationValidator validator) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.auditProperties = null;
        this.postAuditEnrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.managedAuditPipeline = false;
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema) {
        if (!managedAuditPipeline) return prepareCustom(parsedSchema);
        return prepare(parsedSchema, AuditGenerationOptions.defaults(auditProperties));
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema, AuditGenerationOptions auditOptions) {
        Objects.requireNonNull(auditOptions, "auditOptions must not be null");
        DatabaseSchema current = normalizer.normalize(
                Objects.requireNonNull(parsedSchema, "parsedSchema must not be null"));

        if (managedAuditPipeline) {
            current = new AuditColumnSchemaEnricher(
                    auditProperties,
                    auditOptions.includeAuditFields(),
                    auditOptions.auditProfile()).enrich(current);
        }
        for (SchemaEnricher enricher : postAuditEnrichers) {
            current = Objects.requireNonNull(enricher.enrich(current),
                    "schema enricher returned null: " + enricher.getClass().getName());
        }
        current = normalizer.normalize(current);
        return new PreparedSchema(current, validator.validate(current));
    }

    private PreparedSchema prepareCustom(DatabaseSchema parsedSchema) {
        DatabaseSchema current = normalizer.normalize(
                Objects.requireNonNull(parsedSchema, "parsedSchema must not be null"));
        for (SchemaEnricher enricher : postAuditEnrichers) {
            current = Objects.requireNonNull(enricher.enrich(current),
                    "schema enricher returned null: " + enricher.getClass().getName());
        }
        current = normalizer.normalize(current);
        return new PreparedSchema(current, validator.validate(current));
    }
}
