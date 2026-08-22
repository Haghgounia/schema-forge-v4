package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationGenerationServiceTest {
    @Test
    void generatesArtifactFromLiveMetadataAndDesiredTable() {
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", 50)))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", 100)))
                .build();
        MigrationGenerationService service = new MigrationGenerationService(
                new SchemaDiffEngine(), new MigrationSqlRenderer(),
                new FlywayMigrationNamer(Clock.fixed(Instant.parse("2026-08-21T22:05:06Z"), ZoneOffset.UTC)));

        MigrationArtifact artifact = service.generate(
                DatabasePlatform.ORACLE,
                new InMemoryMetadataRepository(List.of(), List.of(live)),
                desired,
                MigrationRenderOptions.safeDefaults());

        assertEquals("V20260821220506000__APP_CUSTOMER_ALTER.sql", artifact.fileName());
        assertEquals(1, artifact.plan().columnChanges().size());
        assertTrue(artifact.sql().contains("MODIFY (NAME VARCHAR2(100 CHAR))"));
    }
}
