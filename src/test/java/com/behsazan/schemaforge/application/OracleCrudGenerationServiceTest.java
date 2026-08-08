package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudPackageGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Tests orchestration of Oracle metadata lookup, CRUD package generation and artifact naming.
 *
 * <p>The suite covers the successful live-metadata path and the fail-fast behavior used when
 * the Oracle metadata repository is disabled or unavailable.</p>
 */
class OracleCrudGenerationServiceTest {

    @Test
    void loadsOracleMetadataAndReturnsSqlArtifact() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        MetadataRepository repository = mock(MetadataRepository.class);
        when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(repository);
        when(repository.available()).thenReturn(true);
        when(repository.findTable("BIM", "PROVINCES")).thenReturn(Optional.of(table()));
        OutputFileNamer namer = new OutputFileNamer(Clock.fixed(
                Instant.parse("2026-08-02T10:11:12.345Z"), ZoneId.of("UTC")));
        OracleCrudGenerationService service = new OracleCrudGenerationService(
                resolver,
                new OracleCrudPackageGenerator(),
                new OracleCrudGenerationOptions(1000, List.of()),
                namer);

        var result = service.generate("bim", "provinces");

        assertEquals("BIM.PROVINCES_20260802_101112_345.oracle.crud-package.sql", result.fileName());
        assertTrue(result.sql().contains("CREATE OR REPLACE PACKAGE BIM.PKG_PROVINCES"));
    }

    @Test
    void rejectsRequestWhenOracleMetadataIsDisabled() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        MetadataRepository repository = MetadataRepository.empty();
        when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(repository);
        OracleCrudGenerationService service = new OracleCrudGenerationService(
                resolver,
                new OracleCrudPackageGenerator(),
                OracleCrudGenerationOptions.defaults());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.generate("BIM", "PROVINCES"));

        assertTrue(error.getMessage().contains("not enabled"));
    }

    private static Table table() {
        return Table.builder("BIM", "PROVINCES")
                .addColumn(new Column(
                        Identifier.of("PROVINCE_ID"),
                        DataType.numeric("NUMBER", 2, 0),
                        false,
                        null,
                        null,
                        true,
                        1))
                .addColumn(Column.required("PROVINCE_CODE", DataType.numeric("NUMBER", 10, 0)))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_PROVINCES"),
                        List.of(Identifier.of("PROVINCE_ID"))))
                .build();
    }
}
