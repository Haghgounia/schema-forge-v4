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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for lossless canonical domain/snapshot round trips. */
class CanonicalSnapshotMapperTest {

    @Test
    void preservesCanonicalSchemaSemanticsAcrossRoundTrip() {
        Table table = Table.builder("TSTSHMA", "CUSTOMER")
                .persianName("مشتری")
                .description("Customer table")
                .addColumn(new Column(Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0), false,
                        new DefaultValue("0"), new Description("identifier"), true, 1, null))
                .addColumn(new Column(Identifier.of("NAME"), DataType.varchar("VARCHAR", 120, LengthSemantics.CHAR),
                        true, null, new Description("name"), false, 2, null))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_NAME"), List.of(Identifier.of("NAME"))))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMER_PARENT"), List.of(Identifier.of("ID")),
                        QualifiedName.of("TSTSHMA", "PARENT"), List.of(Identifier.of("ID")),
                        ReferentialAction.CASCADE, ReferentialAction.NO_ACTION, true, false, true, true))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMER_ID"), "ID >= 0"))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_NAME"),
                        List.of(new IndexColumn(Identifier.of("NAME"), SortDirection.DESC)),
                        IndexType.NORMAL, new Description("lookup"), List.of(Identifier.of("ID")), "NAME IS NOT NULL"))
                .physicalOption("tablespace", "TS_DATA")
                .build();
        DatabaseSchema original = DatabaseSchema.builder("TSTSHMA")
                .description("schema")
                .metadata("source.kind", "word")
                .addTable(table)
                .addSequence(new Sequence(QualifiedName.of("TSTSHMA", "SEQ_CUSTOMER"), 10, 2,
                        1L, 999L, true, 20, new Description("sequence")))
                .build();

        CanonicalSchemaSnapshot.SourceSnapshot source = new CanonicalSchemaSnapshot.SourceSnapshot(
                "Customer/Customer.doc", "Customer.doc", "abc", 100L,
                "2026-08-08T00:00:00Z", "legacy-word");
        CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
        CanonicalSchemaSnapshot snapshot = mapper.toSnapshot(original, source, "2026-08-08T00:00:01Z");
        DatabaseSchema restored = mapper.toDomain(snapshot);

        assertEquals(original.name(), restored.name());
        assertEquals(original.description(), restored.description());
        assertEquals(original.metadata(), restored.metadata());
        assertEquals(original.sequences(), restored.sequences());
        assertEquals(1, restored.tables().size());
        Table actual = restored.tables().getFirst();
        assertEquals(table.qualifiedName(), actual.qualifiedName());
        assertEquals(table.persianName(), actual.persianName());
        assertEquals(table.description(), actual.description());
        assertEquals(table.columns(), actual.columns());
        assertEquals(table.primaryKey(), actual.primaryKey());
        assertEquals(table.uniqueKeys(), actual.uniqueKeys());
        assertEquals(table.foreignKeys(), actual.foreignKeys());
        assertEquals(table.checkConstraints(), actual.checkConstraints());
        assertEquals(table.indexes(), actual.indexes());
        assertEquals(table.physicalOptions(), actual.physicalOptions());
        assertTrue(CanonicalSnapshotVersions.cacheCompatible(snapshot));
    }
    @Test
    void distinguishesPersistedSourceCompatibilityFromWordCacheFreshness() {
        DatabaseSchema schema = DatabaseSchema.builder("TSTSHMA")
                .addTable(Table.builder("TSTSHMA", "SAMPLE")
                        .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                        .build())
                .build();
        CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
        CanonicalSchemaSnapshot current = mapper.toSnapshot(schema,
                new CanonicalSchemaSnapshot.SourceSnapshot(
                        "legacy/SAMPLE.doc", "SAMPLE.doc", "abc", 10L,
                        "2026-08-08T00:00:00Z", "legacy-word"),
                "2026-08-08T00:00:01Z");
        CanonicalSchemaSnapshot stale = new CanonicalSchemaSnapshot(
                current.snapshotVersion(), current.modelVersion(), "word-pipeline-v4-2026-08-08",
                current.generatedAtUtc(), current.source(), current.schema());

        assertTrue(CanonicalSnapshotVersions.contractCompatible(stale));
        assertFalse(CanonicalSnapshotVersions.parserCurrent(stale));
        assertFalse(CanonicalSnapshotVersions.cacheCompatible(stale));
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(stale));
        DatabaseSchema restored = mapper.toDomainPersistedSource(stale);
        assertEquals(schema.name(), restored.name());
        assertEquals(1, restored.tables().size());
        Table expectedTable = schema.tables().getFirst();
        Table actualTable = restored.tables().getFirst();
        assertEquals(expectedTable.qualifiedName(), actualTable.qualifiedName());
        assertEquals(expectedTable.columns(), actualTable.columns());
    }

}
