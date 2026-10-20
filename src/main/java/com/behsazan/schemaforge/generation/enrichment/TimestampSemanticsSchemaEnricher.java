package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.application.TimestampSemantics;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;

/**
 * Applies a request-level semantic to columns whose canonical type is exactly TIMESTAMP.
 *
 * <p>In the managed preparation pipeline it runs after audit enrichment, so user selections also
 * affect newly synthesized audit TIMESTAMP columns. Explicit timezone qualifiers and other
 * datatypes remain authoritative.</p>
 */
public final class TimestampSemanticsSchemaEnricher implements SchemaEnricher {
    private final TimestampSemantics semantics;

    public TimestampSemanticsSchemaEnricher(TimestampSemantics semantics) {
        this.semantics = Objects.requireNonNull(semantics, "timestamp semantics must not be null");
    }

    @Override
    public DatabaseSchema enrich(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        if (!semantics.rewritesUnqualifiedTimestamp()) return schema;

        DatabaseSchema.Builder result = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(result::metadata);
        schema.sequences().forEach(result::addSequence);
        schema.tables().stream().map(this::rewriteTable).forEach(result::addTable);
        return result.build();
    }

    private Table rewriteTable(Table table) {
        String schemaName = table.qualifiedName().schemaName()
                .map(Identifier::value)
                .orElse(null);
        Table.Builder result = Table.builder(schemaName, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());
        table.columns().stream().map(this::rewriteColumn).forEach(result::addColumn);
        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);
        return result.build();
    }

    private Column rewriteColumn(Column column) {
        DataType source = column.dataType();
        if (!"TIMESTAMP".equals(source.name().normalized())) return column;

        DataType rewritten = new DataType(
                Identifier.of(semantics.canonicalTypeName()),
                source.length(),
                source.lengthSemantics(),
                source.precision(),
                source.scale());
        return new Column(
                column.name(), rewritten, column.nullable(), column.defaultValue(), column.description(),
                column.identity(), column.ordinalPosition(), column.generatedExpression(), column.physicalOptions());
    }
}
