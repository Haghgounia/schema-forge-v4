package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexBuildOptionsP5Test {

    @Test
    void oracleOnlineIsRenderedAsAnExplicitBuildDirective() {
        String sql = ddl(new OracleDialect(), Map.of("ONLINE", "ON"));

        assertTrue(sql.contains("PROMPT [INDEX BUILD REVIEW][ORACLE]"));
        assertTrue(sql.contains(" ONLINE;"));
    }

    @Test
    void postgresqlConcurrentlyIsRenderedAfterTheIndexKeyword() {
        String sql = ddl(new PostgreSqlDialect(), Map.of("CONCURRENTLY", "ON"));

        assertTrue(sql.contains("CREATE INDEX CONCURRENTLY"));
        assertTrue(sql.contains("cannot run inside a transaction block"));
    }

    @Test
    void sqlServerRendersCompatibleOperationalBuildOptionsTogether() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("ONLINE", "ON");
        options.put("RESUMABLE", "ON");
        options.put("MAX_DURATION_MINUTES", "30");
        options.put("MAXDOP", "4");
        options.put("SORT_IN_TEMPDB", "OFF");

        String sql = ddl(new SqlServerDialect(), options);

        assertTrue(sql.contains("WITH (ONLINE = ON, RESUMABLE = ON, MAX_DURATION = 30 MINUTES, MAXDOP = 4, SORT_IN_TEMPDB = OFF)"));
        assertTrue(sql.contains("[INDEX BUILD REVIEW][SQLSERVER]"));
    }

    @Test
    void sqlServerDoesNotInferOnlineForResumableOrEmitConflictingSortInTempdb() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("ONLINE", "OFF");
        options.put("RESUMABLE", "ON");
        options.put("MAX_DURATION_MINUTES", "20");
        options.put("SORT_IN_TEMPDB", "ON");

        String sql = ddl(new SqlServerDialect(), options);

        assertTrue(sql.contains("RESUMABLE=ON requires explicit ONLINE=ON"));
        assertFalse(sql.contains("RESUMABLE = ON"));
        assertFalse(sql.contains("MAX_DURATION = 20 MINUTES"));
    }

    @Test
    void snapshotRoundTripKeepsBuildOptionsSeparateFromPhysicalOptions() {
        Index index = index(Map.of("ONLINE", "ON"), Map.of("INDEX_FILLFACTOR", "80"));
        Table table = table(index);
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();
        CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
        CanonicalSchemaSnapshot snapshot = mapper.toSnapshot(
                schema,
                new CanonicalSchemaSnapshot.SourceSnapshot("x.docx", "x.docx", "abc", 1L, "2026-08-17T00:00:00Z", "test"),
                "2026-08-17T00:00:00Z");

        Index restored = mapper.toDomain(snapshot).tables().getFirst().indexes().getFirst();

        assertEquals("ON", restored.buildOptions().get("ONLINE"));
        assertEquals("80", restored.physicalOptions().get("INDEX_FILLFACTOR"));
        assertFalse(restored.physicalOptions().containsKey("ONLINE"));
    }

    private String ddl(com.behsazan.schemaforge.dialect.Dialect dialect, Map<String, String> buildOptions) {
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addTable(table(index(buildOptions, Map.of())))
                .build();
        return new DdlGenerator(dialect).generate(schema);
    }

    private Table table(Index index) {
        return Table.builder("APP", "T")
                .addColumn(Column.required("C", DataType.simple("INTEGER")))
                .addIndex(index)
                .build();
    }

    private Index index(Map<String, String> buildOptions, Map<String, String> physicalOptions) {
        return new Index(
                Identifier.of("IX_T_C"),
                List.of(new IndexColumn(Identifier.of("C"), SortDirection.ASC)),
                IndexType.NORMAL,
                Description.empty(),
                List.of(),
                null,
                physicalOptions,
                buildOptions);
    }
}
