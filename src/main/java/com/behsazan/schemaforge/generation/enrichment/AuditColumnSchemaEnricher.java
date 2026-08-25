package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds missing audit columns without overwriting audit semantics declared by the source model.
 *
 * <p>Two naming conventions are supported. AUTO detects the convention already present in each
 * table and completes only that family. If both families are present in one source table, enrichment
 * fails rather than creating a mixed six/seven-column audit set.</p>
 */
public final class AuditColumnSchemaEnricher implements SchemaEnricher {
    private static final Pattern TYPE_WITH_SIZE = Pattern.compile(
            "^([A-Z0-9_]+)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?(?:\\s+(CHAR|BYTE))?\\s*\\)$");

    private static final List<String> CREATED_UPDATED = List.of(
            "CREATED_AT", "CREATED_BY", "UPDATED_AT", "UPDATED_BY");
    private static final List<String> CREATED_LAST_MODIFIED = List.of(
            "CREATED_BY", "CREATED_DATE", "LAST_MODIFIED_BY", "LAST_MODIFIED_DATE");

    private static final Set<String> CREATED_UPDATED_SIGNALS = Set.of(
            "CREATED_AT", "UPDATED_AT", "UPDATED_BY");
    private static final Set<String> CREATED_LAST_MODIFIED_SIGNALS = Set.of(
            "CREATED_DATE", "LAST_MODIFIED_DATE", "LAST_MODIFIED_BY");

    private final AuditProperties properties;
    private final boolean includeAuditFields;
    private final AuditProfile requestedProfile;

    public AuditColumnSchemaEnricher() {
        this(AuditProperties.defaults(), AuditProperties.defaults().isEnabled(), AuditProfile.AUTO);
    }

    public AuditColumnSchemaEnricher(AuditProperties properties) {
        this(properties, properties.isEnabled(), AuditProfile.AUTO);
    }

    public AuditColumnSchemaEnricher(
            AuditProperties properties,
            boolean includeAuditFields,
            AuditProfile requestedProfile) {
        this.properties = Objects.requireNonNull(properties, "audit properties must not be null");
        this.includeAuditFields = includeAuditFields;
        this.requestedProfile = Objects.requireNonNull(requestedProfile, "auditProfile must not be null");
    }

    @Override
    public DatabaseSchema enrich(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        if (!includeAuditFields) return schema;

        AuditProfile autoFallbackProfile = schemaAutoFallbackProfile(schema);
        DatabaseSchema.Builder result = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(result::metadata);
        schema.tables().stream()
                .map(table -> enrichTable(table, autoFallbackProfile))
                .forEach(result::addTable);
        schema.sequences().forEach(result::addSequence);
        return result.build();
    }

    private Table enrichTable(Table table, AuditProfile autoFallbackProfile) {
        Set<String> existingNames = table.columns().stream()
                .map(column -> column.name().normalized())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        AuditProfile effectiveProfile = effectiveProfile(table, existingNames, autoFallbackProfile);
        List<AuditProperties.AuditColumn> definitions = definitions(effectiveProfile);

        String schemaName = table.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        Table.Builder result = Table.builder(schemaName, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());

        int ordinal = 1;
        for (Column column : table.columns()) {
            // Preserve source datatype, nullability, default, description, identity and physical options.
            result.addColumn(withOrdinal(column, ordinal++));
        }
        for (AuditProperties.AuditColumn definition : definitions) {
            String normalized = Identifier.of(definition.getName()).normalized();
            if (!existingNames.contains(normalized)) {
                result.addColumn(toColumn(definition, ordinal++));
                existingNames.add(normalized);
            }
        }

        copyTableSemantics(table, result);
        return result.build();
    }

    private AuditProfile effectiveProfile(
            Table table,
            Set<String> existingNames,
            AuditProfile autoFallbackProfile) {
        boolean createdUpdated = existingNames.stream().anyMatch(CREATED_UPDATED_SIGNALS::contains);
        boolean createdLastModified = existingNames.stream().anyMatch(CREATED_LAST_MODIFIED_SIGNALS::contains);

        if (createdUpdated && createdLastModified) {
            throw conflict(table, "source table contains fields from both supported audit profiles");
        }
        if (requestedProfile == AuditProfile.CREATED_UPDATED && createdLastModified) {
            throw conflict(table, "requested CREATED_UPDATED but source table uses CREATED_LAST_MODIFIED");
        }
        if (requestedProfile == AuditProfile.CREATED_LAST_MODIFIED && createdUpdated) {
            throw conflict(table, "requested CREATED_LAST_MODIFIED but source table uses CREATED_UPDATED");
        }
        if (requestedProfile != AuditProfile.AUTO) return requestedProfile;
        if (createdUpdated) return AuditProfile.CREATED_UPDATED;
        if (createdLastModified) return AuditProfile.CREATED_LAST_MODIFIED;
        // CREATED_BY alone is ambiguous. When the surrounding source model consistently uses one
        // family, inherit that family; otherwise preserve the historical configured/default family.
        return autoFallbackProfile;
    }

    private AuditProfile schemaAutoFallbackProfile(DatabaseSchema schema) {
        if (requestedProfile != AuditProfile.AUTO) return requestedProfile;
        boolean hasCreatedUpdated = false;
        boolean hasCreatedLastModified = false;
        for (Table table : schema.tables()) {
            Set<String> names = table.columns().stream()
                    .map(column -> column.name().normalized())
                    .collect(java.util.stream.Collectors.toSet());
            boolean createdUpdated = names.stream().anyMatch(CREATED_UPDATED_SIGNALS::contains);
            boolean createdLastModified = names.stream().anyMatch(CREATED_LAST_MODIFIED_SIGNALS::contains);
            if (createdUpdated && createdLastModified) {
                throw conflict(table, "source table contains fields from both supported audit profiles");
            }
            hasCreatedUpdated |= createdUpdated;
            hasCreatedLastModified |= createdLastModified;
        }
        if (hasCreatedUpdated && !hasCreatedLastModified) return AuditProfile.CREATED_UPDATED;
        if (hasCreatedLastModified && !hasCreatedUpdated) return AuditProfile.CREATED_LAST_MODIFIED;
        // No profile evidence, or different tables use different conventions: do not guess a new
        // document-wide convention. Retain the historical SchemaForge default for unclassified tables.
        return AuditProfile.CREATED_LAST_MODIFIED;
    }

    private IllegalArgumentException conflict(Table table, String reason) {
        return new IllegalArgumentException(
                "AUDIT_PROFILE_CONFLICT: " + table.qualifiedName() + " - " + reason);
    }

    private List<AuditProperties.AuditColumn> definitions(AuditProfile profile) {
        return switch (profile) {
            case CREATED_UPDATED -> List.of(
                    new AuditProperties.AuditColumn("CREATED_AT", "TIMESTAMP(6)", false, "Creation timestamp"),
                    new AuditProperties.AuditColumn("CREATED_BY", "VARCHAR2(50 CHAR)", false, "Creation user"),
                    new AuditProperties.AuditColumn("UPDATED_AT", "TIMESTAMP(6)", true, "Last update timestamp"),
                    new AuditProperties.AuditColumn("UPDATED_BY", "VARCHAR2(100 CHAR)", true, "Last update user"));
            case CREATED_LAST_MODIFIED -> configuredCreatedLastModifiedColumns();
            case AUTO -> throw new IllegalStateException("AUTO must be resolved before audit definitions are selected");
        };
    }

    private List<AuditProperties.AuditColumn> configuredCreatedLastModifiedColumns() {
        Map<String, AuditProperties.AuditColumn> configuredByName = new LinkedHashMap<>();
        for (AuditProperties.AuditColumn configured : properties.getColumns()) {
            if (configured == null || configured.getName() == null || configured.getName().isBlank()) {
                throw new IllegalArgumentException("Audit column name must not be blank");
            }
            String normalizedName = Identifier.of(configured.getName()).normalized();
            if (configuredByName.putIfAbsent(normalizedName, configured) != null) {
                throw new IllegalArgumentException("Duplicate audit column configuration: " + normalizedName);
            }
        }
        if (!configuredByName.keySet().equals(Set.copyOf(CREATED_LAST_MODIFIED))) {
            throw new IllegalArgumentException(
                    "Audit configuration must contain exactly these columns: "
                            + String.join(", ", CREATED_LAST_MODIFIED));
        }
        List<AuditProperties.AuditColumn> result = new ArrayList<>();
        for (String name : CREATED_LAST_MODIFIED) result.add(configuredByName.get(name));
        return List.copyOf(result);
    }

    private static void copyTableSemantics(Table table, Table.Builder result) {
        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);
    }

    private Column withOrdinal(Column source, int ordinalPosition) {
        return new Column(
                source.name(), source.dataType(), source.nullable(), source.defaultValue(), source.description(),
                source.identity(), ordinalPosition, source.generatedExpression(), source.physicalOptions());
    }

    private Column toColumn(AuditProperties.AuditColumn configured, int ordinalPosition) {
        if (configured.getDataType() == null || configured.getDataType().isBlank()) {
            throw new IllegalArgumentException("Audit column dataType must not be blank: " + configured.getName());
        }
        return new Column(
                Identifier.of(configured.getName()),
                parseDataType(configured.getDataType()),
                configured.isNullable(),
                null,
                new Description(configured.getComment()),
                false,
                ordinalPosition,
                null);
    }

    private DataType parseDataType(String rawType) {
        String normalized = rawType.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = TYPE_WITH_SIZE.matcher(normalized);
        if (!matcher.matches()) return DataType.simple(normalized);

        String name = matcher.group(1);
        int first = Integer.parseInt(matcher.group(2));
        String second = matcher.group(3);
        String lengthSemantics = matcher.group(4);
        if (second != null) return DataType.numeric(name, first, Integer.parseInt(second));
        if (name.contains("CHAR") || name.contains("VARCHAR")) {
            return DataType.varchar(name, first,
                    "BYTE".equals(lengthSemantics)
                            ? com.behsazan.schemaforge.domain.valueobject.LengthSemantics.BYTE
                            : com.behsazan.schemaforge.domain.valueobject.LengthSemantics.CHAR);
        }
        return DataType.numeric(name, first, null);
    }
}
