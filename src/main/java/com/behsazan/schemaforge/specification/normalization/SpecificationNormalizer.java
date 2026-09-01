package com.behsazan.schemaforge.specification.normalization;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.naming.LogicalObjectNamingPolicy;

import java.util.Objects;

/**
 * DBMS-independent canonical normalization.
 *
 * <p>All generated database-object names are recomputed from one cross-service naming policy.
 * Names carried by Word, Legacy Word, EA/XMI or JSON input are not authoritative.</p>
 */
public final class SpecificationNormalizer {
    public DatabaseSchema normalize(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        DatabaseSchema.Builder normalized = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(normalized::metadata);
        schema.sequences().forEach(normalized::addSequence);
        for (Table table : schema.tables()) {
            normalized.addTable(normalize(table));
        }
        return normalized.build();
    }

    /** Normalizes one table for direct DDL/migration callers that bypass the REST preparation pipeline. */
    public Table normalize(Table table) {
        String schema = table.qualifiedName().schemaName().map(identifier -> identifier.value()).orElse(null);
        Table.Builder builder = Table.builder(schema, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());
        table.columns().forEach(builder::addColumn);
        table.physicalOptions().forEach(builder::physicalOption);

        table.primaryKey().ifPresent(primaryKey -> builder.primaryKey(new PrimaryKey(
                LogicalObjectNamingPolicy.primaryKey(table, primaryKey),
                primaryKey.columns(), primaryKey.deferrable(), primaryKey.initiallyDeferred(),
                primaryKey.physicalOptions())));

        for (UniqueKey uniqueKey : table.uniqueKeys()) {
            builder.addUniqueKey(new UniqueKey(
                    LogicalObjectNamingPolicy.uniqueKey(table, uniqueKey),
                    uniqueKey.columns(), uniqueKey.deferrable(), uniqueKey.initiallyDeferred(),
                    uniqueKey.physicalOptions()));
        }
        for (ForeignKey foreignKey : table.foreignKeys()) {
            builder.addForeignKey(new ForeignKey(
                    LogicalObjectNamingPolicy.foreignKey(table, foreignKey),
                    foreignKey.columns(), foreignKey.referencedTable(), foreignKey.referencedColumns(),
                    foreignKey.onDelete(), foreignKey.onUpdate(), foreignKey.deferrable(),
                    foreignKey.initiallyDeferred(), foreignKey.physicalReference(), foreignKey.schemaExplicit()));
        }
        for (CheckConstraint check : table.checkConstraints()) {
            builder.addCheck(new CheckConstraint(
                    LogicalObjectNamingPolicy.checkConstraint(table, check), check.expression()));
        }
        for (Index index : table.indexes()) {
            builder.addIndex(new Index(
                    LogicalObjectNamingPolicy.index(table, index),
                    index.columns(), index.type(), index.description(), index.includeColumns(),
                    index.predicate(), index.physicalOptions(), index.buildOptions()));
        }
        return builder.build();
    }
}
