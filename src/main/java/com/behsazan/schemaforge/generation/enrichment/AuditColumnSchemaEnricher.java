package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standardizes the four mandatory audit columns at the end of every table.
 *
 * <p>If an input document already declares one or more standard audit columns,
 * those declarations are replaced by the configured standard definitions and
 * moved to the end. This guarantees identical column order and definitions for
 * Word, EA/XMI, JSON/Excel and every SQL dialect.</p>
 */
public final class AuditColumnSchemaEnricher implements SchemaEnricher {
    private static final Pattern TYPE_WITH_SIZE = Pattern.compile(
            "^([A-Z0-9_]+)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

    private static final List<String> REQUIRED_AUDIT_COLUMNS = List.of(
            "CREATED_BY",
            "CREATED_DATE",
            "LAST_MODIFIED_BY",
            "LAST_MODIFIED_DATE"
    );

    private final AuditProperties properties;

    public AuditColumnSchemaEnricher() {
        this(AuditProperties.defaults());
    }

    public AuditColumnSchemaEnricher(AuditProperties properties) {
        this.properties = Objects.requireNonNull(properties, "audit properties must not be null");
    }

    @Override
    public DatabaseSchema enrich(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        if (!properties.isEnabled()) {
            return schema;
        }

        List<AuditProperties.AuditColumn> configuredAuditColumns = requiredAuditColumns();
        Set<String> auditNames = Set.copyOf(REQUIRED_AUDIT_COLUMNS);

        DatabaseSchema.Builder result = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(result::metadata);
        schema.tables().stream()
                .map(table -> enrichTable(table, configuredAuditColumns, auditNames))
                .forEach(result::addTable);
        schema.sequences().forEach(result::addSequence);
        return result.build();
    }

    private Table enrichTable(
            Table table,
            List<AuditProperties.AuditColumn> configuredAuditColumns,
            Set<String> auditNames) {

        String schemaName = table.qualifiedName().schemaName()
                .map(value -> value.value())
                .orElse(null);
        Table.Builder result = Table.builder(schemaName, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());

        int ordinal = 1;
        for (Column column : table.columns()) {
            if (!auditNames.contains(column.name().normalized())) {
                result.addColumn(withOrdinal(column, ordinal++));
            }
        }

        for (AuditProperties.AuditColumn configured : configuredAuditColumns) {
            result.addColumn(toColumn(configured, ordinal++));
        }

        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);
        return result.build();
    }

    private List<AuditProperties.AuditColumn> requiredAuditColumns() {
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

        if (!configuredByName.keySet().equals(Set.copyOf(REQUIRED_AUDIT_COLUMNS))) {
            throw new IllegalArgumentException(
                    "Audit configuration must contain exactly these columns: "
                            + String.join(", ", REQUIRED_AUDIT_COLUMNS));
        }

        return REQUIRED_AUDIT_COLUMNS.stream()
                .map(configuredByName::get)
                .toList();
    }

    private Column withOrdinal(Column source, int ordinalPosition) {
        return new Column(
                source.name(),
                source.dataType(),
                source.nullable(),
                source.defaultValue(),
                source.description(),
                source.identity(),
                ordinalPosition,
                source.generatedExpression(),
                source.physicalOptions()
        );
    }

    private Column toColumn(AuditProperties.AuditColumn configured, int ordinalPosition) {
        if (configured.getDataType() == null || configured.getDataType().isBlank()) {
            throw new IllegalArgumentException(
                    "Audit column dataType must not be blank: " + configured.getName());
        }
        return new Column(
                Identifier.of(configured.getName()),
                parseDataType(configured.getDataType()),
                configured.isNullable(),
                null,
                new Description(configured.getComment()),
                false,
                ordinalPosition,
                null
        );
    }

    private DataType parseDataType(String rawType) {
        String normalized = rawType.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = TYPE_WITH_SIZE.matcher(normalized);
        if (!matcher.matches()) {
            return DataType.simple(normalized);
        }

        String name = matcher.group(1);
        int first = Integer.parseInt(matcher.group(2));
        String second = matcher.group(3);
        if (second != null) {
            return DataType.numeric(name, first, Integer.parseInt(second));
        }
        if (name.contains("CHAR") || name.contains("VARCHAR")) {
            return DataType.varchar(name, first);
        }
        return DataType.numeric(name, first, null);
    }
}
