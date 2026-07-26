package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds configured standard audit columns to the end of every table when absent. */
public final class AuditColumnSchemaEnricher implements SchemaEnricher {
    private static final Pattern TYPE_WITH_SIZE = Pattern.compile("^([A-Z0-9_]+)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

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
                .description(table.description().value());

        table.columns().forEach(result::addColumn);
        int nextOrdinal = table.columns().stream()
                .map(Column::ordinalPosition)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        for (AuditProperties.AuditColumn configured : properties.getColumns()) {
            if (table.findColumn(configured.getName()).isEmpty()) {
                result.addColumn(toColumn(configured, nextOrdinal++));
            }
        }

        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);
        return result.build();
    }

    private Column toColumn(AuditProperties.AuditColumn configured, int ordinalPosition) {
        if (configured.getName() == null || configured.getName().isBlank()) {
            throw new IllegalArgumentException("Audit column name must not be blank");
        }
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
