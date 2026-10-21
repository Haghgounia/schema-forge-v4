package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.enrichment.AuditColumnSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.DefaultValueSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.GrantSchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.SchemaEnricher;
import com.behsazan.schemaforge.generation.enrichment.TimestampSemanticsSchemaEnricher;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.validation.DefaultCheckCompatibilityValidator;
import com.behsazan.schemaforge.specification.validation.NamingConventionValidator;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.specification.validation.spelling.LanguageToolSpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.OfflineHunspellSpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.NoOpSpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellCheckService;
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
    private final NamingConventionValidator namingConventionValidator;
    private final DefaultCheckCompatibilityValidator defaultCheckCompatibilityValidator;
    private final boolean managedAuditPipeline;

    public SchemaPreparationService() {
        this(AuditProperties.defaults(), GrantProperties.defaults(), SpellCheckProperties.defaults(), NamingValidationProperties.defaults(), new ObjectMapper());
    }

    public SchemaPreparationService(AuditProperties auditProperties) {
        this(auditProperties, GrantProperties.defaults(), SpellCheckProperties.defaults(), NamingValidationProperties.defaults(), new ObjectMapper());
    }

    public SchemaPreparationService(
            AuditProperties auditProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper) {
        this(auditProperties, GrantProperties.defaults(), spellCheckProperties, NamingValidationProperties.defaults(), objectMapper);
    }

    public SchemaPreparationService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper) {
        this(auditProperties, grantProperties, spellCheckProperties, NamingValidationProperties.defaults(), objectMapper);
    }

    public SchemaPreparationService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            NamingValidationProperties namingValidationProperties,
            ObjectMapper objectMapper) {
        this.normalizer = new SpecificationNormalizer();
        this.auditProperties = Objects.requireNonNull(auditProperties, "audit properties must not be null");
        this.postAuditEnrichers = List.of(
                new DefaultValueSchemaEnricher(),
                new GrantSchemaEnricher(grantProperties));
        this.validator = new SpecificationValidator(createSpellCheck(spellCheckProperties, objectMapper));
        this.namingConventionValidator = new NamingConventionValidator(
                Objects.requireNonNull(namingValidationProperties, "naming validation properties must not be null"));
        this.defaultCheckCompatibilityValidator = new DefaultCheckCompatibilityValidator();
        this.managedAuditPipeline = true;
    }


    private static SpellCheckService createSpellCheck(SpellCheckProperties properties, ObjectMapper mapper) {
        Objects.requireNonNull(properties, "spell-check properties must not be null");
        if (!properties.isEnabled()) return new NoOpSpellCheckService();
        String provider = Objects.requireNonNull(properties.getProvider(), "spell-check provider must not be null")
                .trim().toUpperCase(java.util.Locale.ROOT);
        return switch (provider) {
            case "OFFLINE" -> new OfflineHunspellSpellCheckService(properties);
            case "LANGUAGETOOL" -> new LanguageToolSpellCheckService(properties, mapper);
            default -> throw new IllegalArgumentException("Unsupported spell-check provider: " + provider);
        };
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
        this.namingConventionValidator = null;
        this.defaultCheckCompatibilityValidator = null;
        this.managedAuditPipeline = false;
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema) {
        if (!managedAuditPipeline) return prepareCustom(parsedSchema);
        return prepare(parsedSchema, AuditGenerationOptions.defaults(auditProperties), TimestampSemantics.SOURCE);
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema, TimestampSemantics timestampSemantics) {
        if (!managedAuditPipeline) {
            DatabaseSchema current = normalizer.normalize(
                    Objects.requireNonNull(parsedSchema, "parsedSchema must not be null"));
            current = new TimestampSemanticsSchemaEnricher(
                    Objects.requireNonNull(timestampSemantics, "timestampSemantics must not be null")).enrich(current);
            for (SchemaEnricher enricher : postAuditEnrichers) {
                current = Objects.requireNonNull(enricher.enrich(current),
                        "schema enricher returned null: " + enricher.getClass().getName());
            }
            current = normalizer.normalize(current);
            return new PreparedSchema(current, validator.validate(current));
        }
        return prepare(parsedSchema, AuditGenerationOptions.defaults(auditProperties), timestampSemantics);
    }

    public PreparedSchema prepare(DatabaseSchema parsedSchema, AuditGenerationOptions auditOptions) {
        return prepare(parsedSchema, auditOptions, TimestampSemantics.SOURCE);
    }

    public PreparedSchema prepare(
            DatabaseSchema parsedSchema,
            AuditGenerationOptions auditOptions,
            TimestampSemantics timestampSemantics) {
        Objects.requireNonNull(auditOptions, "auditOptions must not be null");
        Objects.requireNonNull(timestampSemantics, "timestampSemantics must not be null");
        DatabaseSchema current = normalizer.normalize(
                Objects.requireNonNull(parsedSchema, "parsedSchema must not be null"));

        // First add only missing audit columns, retaining source-defined datatypes and audit family.
        // The user's timestamp selection then covers BOTH unqualified source TIMESTAMP columns and
        // newly added audit TIMESTAMP columns. Explicit WITH TIME ZONE/LOCAL TIME ZONE types remain
        // authoritative and cannot be overwritten by the timestamp semantics enricher.
        if (managedAuditPipeline) {
            current = new AuditColumnSchemaEnricher(
                    auditProperties,
                    auditOptions.includeAuditFields(),
                    auditOptions.auditProfile()).enrich(current);
        }
        current = new TimestampSemanticsSchemaEnricher(timestampSemantics).enrich(current);
        for (SchemaEnricher enricher : postAuditEnrichers) {
            current = Objects.requireNonNull(enricher.enrich(current),
                    "schema enricher returned null: " + enricher.getClass().getName());
        }
        current = normalizer.normalize(current);
        return new PreparedSchema(current, withCanonicalValidation(current, validator.validate(current)));
    }

    private ValidationReport withCanonicalValidation(DatabaseSchema schema, ValidationReport baseReport) {
        List<ValidationIssue> issues = new java.util.ArrayList<>(baseReport.issues());
        issues.addAll(namingConventionValidator.validate(schema));
        issues.addAll(defaultCheckCompatibilityValidator.validate(schema));
        return new ValidationReport(
                issues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                issues);
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
