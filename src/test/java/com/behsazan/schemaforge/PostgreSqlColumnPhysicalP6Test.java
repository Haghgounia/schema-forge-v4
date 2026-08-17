package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlColumnPhysicalP6Test {

    @Test
    void rendersExplicitColumnStorageAndCompression() {
        Column payload = new Column(
                Identifier.of("PAYLOAD"), DataType.simple("CLOB"), true,
                null, Description.empty(), false, 1, null,
                Map.of("POSTGRESQL_STORAGE", "EXTENDED", "POSTGRESQL_COMPRESSION", "LZ4"));

        String sql = ddl(payload);

        assertTrue(sql.contains("payload TEXT STORAGE EXTENDED COMPRESSION lz4"));
    }

    @Test
    void rejectsCompressionWhenStorageMakesItInactiveOrTypeIsFixedWidth() {
        Column externalText = new Column(
                Identifier.of("EXTERNAL_TEXT"), DataType.simple("CLOB"), true,
                null, Description.empty(), false, 1, null,
                Map.of("POSTGRESQL_STORAGE", "EXTERNAL", "POSTGRESQL_COMPRESSION", "PGLZ"));
        Column integerValue = new Column(
                Identifier.of("INTEGER_VALUE"), DataType.simple("INTEGER"), true,
                null, Description.empty(), false, 2, null,
                Map.of("POSTGRESQL_COMPRESSION", "PGLZ"));

        Table table = Table.builder("BIM", "DOCS")
                .addColumn(externalText)
                .addColumn(integerValue)
                .build();
        String sql = new DdlGenerator(new PostgreSqlDialect()).generate(
                DatabaseSchema.builder("BIM").addTable(table).build());

        assertTrue(sql.contains("[SOURCE PHYSICAL ISSUE][POSTGRESQL]"));
        assertFalse(sql.contains("external_text TEXT STORAGE EXTERNAL COMPRESSION"));
        assertFalse(sql.contains("integer_value INTEGER COMPRESSION"));
    }

    @Test
    void roundTripsColumnPhysicalOptionsThroughCanonicalSnapshot() {
        Column payload = new Column(
                Identifier.of("PAYLOAD"), DataType.simple("CLOB"), true,
                null, Description.empty(), false, 1, null,
                Map.of("POSTGRESQL_STORAGE", "MAIN", "POSTGRESQL_COMPRESSION", "PGLZ"));
        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .addTable(Table.builder("BIM", "DOCS").addColumn(payload).build())
                .build();
        CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
        CanonicalSchemaSnapshot snapshot = mapper.toSnapshot(schema,
                new CanonicalSchemaSnapshot.SourceSnapshot(
                        "docs.docx", "docs.docx", "sha", 1L,
                        "2026-08-17T00:00:00Z", "probe"),
                "2026-08-17T00:00:00Z");

        Column restored = mapper.toDomain(snapshot).tables().getFirst().columns().getFirst();

        assertEquals("MAIN", restored.physicalOptions().get("POSTGRESQL_STORAGE"));
        assertEquals("PGLZ", restored.physicalOptions().get("POSTGRESQL_COMPRESSION"));
    }

    @Test
    void treatsMissingColumnPhysicalOptionsInOlderSnapshotsAsEmpty() {
        CanonicalSchemaSnapshot.ColumnSnapshot column = new CanonicalSchemaSnapshot.ColumnSnapshot(
                "ID",
                new CanonicalSchemaSnapshot.DataTypeSnapshot("INTEGER", null, "DEFAULT", null, null),
                false, null, "", false, 1, null, null);
        CanonicalSchemaSnapshot.TableSnapshot table = new CanonicalSchemaSnapshot.TableSnapshot(
                "BIM", "DOCS", "", "", List.of(column), null,
                List.of(), List.of(), List.of(), List.of(), Map.of());
        CanonicalSchemaSnapshot snapshot = new CanonicalSchemaSnapshot(
                CanonicalSnapshotVersions.SNAPSHOT_VERSION,
                CanonicalSnapshotVersions.MODEL_VERSION,
                CanonicalSnapshotVersions.PARSER_VERSION,
                "2026-08-17T00:00:00Z",
                new CanonicalSchemaSnapshot.SourceSnapshot(
                        "legacy.json", "legacy.json", "sha", 1L,
                        "2026-08-17T00:00:00Z", "legacy"),
                new CanonicalSchemaSnapshot.SchemaSnapshot("BIM", "", Map.of(), List.of(table), List.of()));

        Column restored = new CanonicalSnapshotMapper().toDomainPersistedSource(snapshot)
                .tables().getFirst().columns().getFirst();

        assertTrue(restored.physicalOptions().isEmpty());
    }

    private String ddl(Column column) {
        Table table = Table.builder("BIM", "DOCS").addColumn(column).build();
        return new DdlGenerator(new PostgreSqlDialect()).generate(
                DatabaseSchema.builder("BIM").addTable(table).build());
    }
}
