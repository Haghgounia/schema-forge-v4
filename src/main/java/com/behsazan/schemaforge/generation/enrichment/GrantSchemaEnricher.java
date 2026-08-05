package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Adds configured role grants to every table while preserving explicit table grants. */
public final class GrantSchemaEnricher implements SchemaEnricher {
    private static final String GRANTS_OPTION = "GRANTS";

    private final GrantProperties properties;

    public GrantSchemaEnricher() {
        this(GrantProperties.defaults());
    }

    public GrantSchemaEnricher(GrantProperties properties) {
        this.properties = Objects.requireNonNull(properties, "grant properties must not be null");
    }

    @Override
    public DatabaseSchema enrich(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");

        DatabaseSchema.Builder result = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(result::metadata);
        schema.tables().stream().map(this::enrichTable).forEach(result::addTable);
        schema.sequences().forEach(result::addSequence);
        return result.build();
    }

    private Table enrichTable(Table table) {
        String schemaName = table.qualifiedName().schemaName().map(value -> value.value()).orElse(null);
        Table.Builder result = Table.builder(schemaName, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());

        table.columns().forEach(result::addColumn);
        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);

        List<String> grants = new ArrayList<>();
        existingGrantOption(table).ifPresent(value -> splitGrantLines(value).forEach(grants::add));
        configuredGrantLines().forEach(grants::add);

        Map<String, String> unique = new LinkedHashMap<>();
        for (String grant : grants) {
            String normalized = grant.trim().replaceAll("\\s+", " ");
            if (!normalized.isBlank()) {
                unique.putIfAbsent(normalized.toUpperCase(Locale.ROOT), normalized);
            }
        }
        if (!unique.isEmpty()) {
            result.physicalOption(GRANTS_OPTION, String.join(System.lineSeparator(), unique.values()));
        }
        return result.build();
    }

    private java.util.Optional<String> existingGrantOption(Table table) {
        return table.physicalOptions().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(GRANTS_OPTION))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private List<String> splitGrantLines(String value) {
        return List.of(value.split("[;\\r\\n]+"));
    }

    private List<String> configuredGrantLines() {
        List<String> result = new ArrayList<>();
        for (GrantProperties.GrantRule grant : properties.getGrants()) {
            if (grant == null) {
                continue;
            }
            String grantee = requireText(grant.getGrantee(), "Grant grantee must not be blank");
            List<String> privileges = grant.getPrivileges() == null
                    ? List.of()
                    : grant.getPrivileges().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (privileges.isEmpty()) {
                throw new IllegalArgumentException("Grant privileges must not be empty for role " + grantee);
            }
            result.add(String.join(", ", privileges) + " TO " + grantee);
        }
        return result;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
