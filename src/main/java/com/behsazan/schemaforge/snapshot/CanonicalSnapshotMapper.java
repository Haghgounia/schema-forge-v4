package com.behsazan.schemaforge.snapshot;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lossless mapper between the canonical domain model and the versioned JSON snapshot DTO.
 *
 * <p>The mapper is intentionally free of Jackson annotations and I/O. This keeps persistence
 * concerns out of the domain model and makes round-trip behavior independently testable.</p>
 */
public final class CanonicalSnapshotMapper {

    /** Creates a JSON-friendly snapshot while preserving the source identity used by the cache. */
    public CanonicalSchemaSnapshot toSnapshot(
            DatabaseSchema schema,
            CanonicalSchemaSnapshot.SourceSnapshot source,
            String generatedAtUtc) {

        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(source, "source must not be null");
        return new CanonicalSchemaSnapshot(
                CanonicalSnapshotVersions.SNAPSHOT_VERSION,
                CanonicalSnapshotVersions.MODEL_VERSION,
                CanonicalSnapshotVersions.PARSER_VERSION,
                generatedAtUtc,
                source,
                new CanonicalSchemaSnapshot.SchemaSnapshot(
                        schema.name().value(),
                        schema.description().value(),
                        schema.metadata(),
                        schema.tables().stream().map(this::tableSnapshot).toList(),
                        schema.sequences().stream().map(this::sequenceSnapshot).toList()));
    }

    /** Reconstructs a validated canonical domain model from a current-parser cache snapshot. */
    public DatabaseSchema toDomain(CanonicalSchemaSnapshot snapshot) {
        CanonicalSnapshotVersions.requireCompatible(snapshot);
        return mapToDomain(snapshot);
    }

    /**
     * Reconstructs a canonical domain model from a persisted JSON source whose snapshot/model
     * contract is compatible, even when its parser provenance is older than the current Word parser.
     * This must not be used as the Word-cache reuse check; {@link #toDomain(CanonicalSchemaSnapshot)}
     * remains strict for that purpose.
     */
    public DatabaseSchema toDomainPersistedSource(CanonicalSchemaSnapshot snapshot) {
        CanonicalSnapshotVersions.requireContractCompatible(snapshot);
        return mapToDomain(snapshot);
    }

    private DatabaseSchema mapToDomain(CanonicalSchemaSnapshot snapshot) {
        CanonicalSchemaSnapshot.SchemaSnapshot value = Objects.requireNonNull(snapshot.schema(), "snapshot schema");
        DatabaseSchema.Builder builder = DatabaseSchema.builder(value.name());
        if (value.description() != null) {
            builder.description(value.description());
        }
        safeMap(value.metadata()).forEach(builder::metadata);
        safeList(value.tables()).stream().map(this::table).forEach(builder::addTable);
        safeList(value.sequences()).stream().map(this::sequence).forEach(builder::addSequence);
        return builder.build();
    }

    private CanonicalSchemaSnapshot.TableSnapshot tableSnapshot(Table table) {
        return new CanonicalSchemaSnapshot.TableSnapshot(
                string(table.qualifiedName().schema()),
                table.qualifiedName().name().value(),
                table.persianName().value(),
                table.description().value(),
                table.columns().stream().map(this::columnSnapshot).toList(),
                table.primaryKey().map(this::primaryKeySnapshot).orElse(null),
                table.foreignKeys().stream().map(this::foreignKeySnapshot).toList(),
                table.uniqueKeys().stream().map(this::uniqueKeySnapshot).toList(),
                table.checkConstraints().stream().map(this::checkSnapshot).toList(),
                table.indexes().stream().map(this::indexSnapshot).toList(),
                table.physicalOptions());
    }

    private CanonicalSchemaSnapshot.ColumnSnapshot columnSnapshot(Column column) {
        DataType type = column.dataType();
        return new CanonicalSchemaSnapshot.ColumnSnapshot(
                column.name().value(),
                new CanonicalSchemaSnapshot.DataTypeSnapshot(
                        type.name().value(), type.length(), type.lengthSemantics().name(), type.precision(), type.scale()),
                column.nullable(),
                column.defaultValue().expression(),
                column.description().value(),
                column.identity(),
                column.ordinalPosition(),
                column.generatedExpression());
    }

    private CanonicalSchemaSnapshot.PrimaryKeySnapshot primaryKeySnapshot(PrimaryKey key) {
        return new CanonicalSchemaSnapshot.PrimaryKeySnapshot(
                string(key.name()), strings(key.columns()), key.deferrable(), key.initiallyDeferred());
    }

    private CanonicalSchemaSnapshot.ForeignKeySnapshot foreignKeySnapshot(ForeignKey key) {
        return new CanonicalSchemaSnapshot.ForeignKeySnapshot(
                string(key.name()), strings(key.columns()), string(key.referencedTable().schema()),
                key.referencedTable().name().value(), strings(key.referencedColumns()),
                key.onDelete().name(), key.onUpdate().name(), key.deferrable(), key.initiallyDeferred(),
                key.physicalReference(), key.schemaExplicit());
    }

    private CanonicalSchemaSnapshot.UniqueKeySnapshot uniqueKeySnapshot(UniqueKey key) {
        return new CanonicalSchemaSnapshot.UniqueKeySnapshot(
                string(key.name()), strings(key.columns()), key.deferrable(), key.initiallyDeferred());
    }

    private CanonicalSchemaSnapshot.CheckConstraintSnapshot checkSnapshot(CheckConstraint check) {
        return new CanonicalSchemaSnapshot.CheckConstraintSnapshot(string(check.name()), check.expression());
    }

    private CanonicalSchemaSnapshot.IndexSnapshot indexSnapshot(Index index) {
        return new CanonicalSchemaSnapshot.IndexSnapshot(
                string(index.name()),
                index.columns().stream().map(this::indexColumnSnapshot).toList(),
                index.type().name(),
                index.description().value(),
                strings(index.includeColumns()),
                index.predicate());
    }

    private CanonicalSchemaSnapshot.IndexColumnSnapshot indexColumnSnapshot(IndexColumn column) {
        return new CanonicalSchemaSnapshot.IndexColumnSnapshot(
                string(column.column()), column.expression(), column.direction().name());
    }

    private CanonicalSchemaSnapshot.SequenceSnapshot sequenceSnapshot(Sequence sequence) {
        return new CanonicalSchemaSnapshot.SequenceSnapshot(
                string(sequence.qualifiedName().schema()), sequence.qualifiedName().name().value(),
                sequence.startWith(), sequence.incrementBy(), sequence.minValue(), sequence.maxValue(),
                sequence.cycle(), sequence.cacheSize(), sequence.description().value());
    }

    private Table table(CanonicalSchemaSnapshot.TableSnapshot value) {
        Table.Builder builder = Table.builder(value.schema(), value.name());
        if (value.persianName() != null) builder.persianName(value.persianName());
        if (value.description() != null) builder.description(value.description());
        safeList(value.columns()).stream().map(this::column).forEach(builder::addColumn);
        if (value.primaryKey() != null) builder.primaryKey(primaryKey(value.primaryKey()));
        safeList(value.foreignKeys()).stream().map(this::foreignKey).forEach(builder::addForeignKey);
        safeList(value.uniqueKeys()).stream().map(this::uniqueKey).forEach(builder::addUniqueKey);
        safeList(value.checkConstraints()).stream().map(this::check).forEach(builder::addCheck);
        safeList(value.indexes()).stream().map(this::index).forEach(builder::addIndex);
        safeMap(value.physicalOptions()).forEach(builder::physicalOption);
        return builder.build();
    }

    private Column column(CanonicalSchemaSnapshot.ColumnSnapshot value) {
        CanonicalSchemaSnapshot.DataTypeSnapshot type = Objects.requireNonNull(value.dataType(), "column dataType");
        return new Column(
                Identifier.of(value.name()),
                new DataType(
                        Identifier.of(type.name()), type.length(), parseLengthSemantics(type.lengthSemantics()),
                        type.precision(), type.scale()),
                value.nullable(),
                new DefaultValue(value.defaultExpression()),
                new Description(value.description()),
                value.identity(),
                value.ordinalPosition(),
                value.generatedExpression());
    }

    private PrimaryKey primaryKey(CanonicalSchemaSnapshot.PrimaryKeySnapshot value) {
        return new PrimaryKey(identifier(value.name()), identifiers(value.columns()),
                value.deferrable(), value.initiallyDeferred());
    }

    private ForeignKey foreignKey(CanonicalSchemaSnapshot.ForeignKeySnapshot value) {
        return new ForeignKey(
                identifier(value.name()), identifiers(value.columns()),
                QualifiedName.of(value.referencedSchema(), value.referencedTable()),
                identifiers(value.referencedColumns()),
                parseEnum(ReferentialAction.class, value.onDelete(), ReferentialAction.NO_ACTION),
                parseEnum(ReferentialAction.class, value.onUpdate(), ReferentialAction.NO_ACTION),
                value.deferrable(), value.initiallyDeferred(), value.physicalReference(), value.schemaExplicit());
    }

    private UniqueKey uniqueKey(CanonicalSchemaSnapshot.UniqueKeySnapshot value) {
        return new UniqueKey(identifier(value.name()), identifiers(value.columns()),
                value.deferrable(), value.initiallyDeferred());
    }

    private CheckConstraint check(CanonicalSchemaSnapshot.CheckConstraintSnapshot value) {
        return new CheckConstraint(identifier(value.name()), value.expression());
    }

    private Index index(CanonicalSchemaSnapshot.IndexSnapshot value) {
        return new Index(
                identifier(value.name()),
                safeList(value.columns()).stream().map(this::indexColumn).toList(),
                parseEnum(IndexType.class, value.type(), IndexType.NORMAL),
                new Description(value.description()),
                identifiers(value.includeColumns()),
                value.predicate());
    }

    private IndexColumn indexColumn(CanonicalSchemaSnapshot.IndexColumnSnapshot value) {
        SortDirection direction = parseEnum(SortDirection.class, value.direction(), SortDirection.ASC);
        return value.expression() == null
                ? new IndexColumn(Identifier.of(value.column()), direction)
                : IndexColumn.expression(value.expression(), direction);
    }

    private Sequence sequence(CanonicalSchemaSnapshot.SequenceSnapshot value) {
        return new Sequence(
                QualifiedName.of(value.schema(), value.name()), value.startWith(), value.incrementBy(),
                value.minValue(), value.maxValue(), value.cycle(), value.cacheSize(), new Description(value.description()));
    }

    private static LengthSemantics parseLengthSemantics(String value) {
        return parseEnum(LengthSemantics.class, value, LengthSemantics.DEFAULT);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Enum.valueOf(type, value);
    }

    private static Identifier identifier(String value) {
        return value == null || value.isBlank() ? null : Identifier.of(value);
    }

    private static List<Identifier> identifiers(List<String> values) {
        return safeList(values).stream().map(Identifier::of).toList();
    }

    private static List<String> strings(List<Identifier> values) {
        return safeList(values).stream().map(Identifier::value).toList();
    }

    private static String string(Identifier value) {
        return value == null ? null : value.value();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }
}
