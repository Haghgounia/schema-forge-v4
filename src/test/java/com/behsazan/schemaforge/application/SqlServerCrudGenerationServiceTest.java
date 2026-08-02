package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudProcedureGenerator;
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

class SqlServerCrudGenerationServiceTest {

    @Test
    void loadsSqlServerMetadataAndReturnsSqlArtifact() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        MetadataRepository repository = mock(MetadataRepository.class);
        when(resolver.resolve(DatabasePlatform.SQLSERVER)).thenReturn(repository);
        when(repository.available()).thenReturn(true);
        when(repository.findTable("BIM", "PROVINCES")).thenReturn(Optional.of(table()));
        OutputFileNamer namer = new OutputFileNamer(Clock.fixed(
                Instant.parse("2026-08-02T10:11:12.345Z"), ZoneId.of("UTC")));
        SqlServerCrudGenerationService service = new SqlServerCrudGenerationService(
                resolver,
                new SqlServerCrudProcedureGenerator(),
                new SqlServerCrudGenerationOptions(1000, List.of()),
                namer);

        var result = service.generate("bim", "provinces");

        assertEquals("BIM.PROVINCES_20260802_101112_345.sqlserver.crud-procedures.sql", result.fileName());
        assertTrue(result.sql().contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_CREATE]"));
    }

    @Test
    void rejectsRequestWhenSqlServerMetadataIsDisabled() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        MetadataRepository repository = MetadataRepository.empty();
        when(resolver.resolve(DatabasePlatform.SQLSERVER)).thenReturn(repository);
        SqlServerCrudGenerationService service = new SqlServerCrudGenerationService(
                resolver,
                new SqlServerCrudProcedureGenerator(),
                SqlServerCrudGenerationOptions.defaults());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.generate("BIM", "PROVINCES"));

        assertTrue(error.getMessage().contains("not enabled"));
    }

    private static Table table() {
        return Table.builder("BIM", "PROVINCES")
                .addColumn(new Column(
                        Identifier.of("PROVINCE_ID"),
                        DataType.simple("INT"),
                        false,
                        null,
                        null,
                        true,
                        1))
                .addColumn(Column.required("PROVINCE_CODE", DataType.numeric("DECIMAL", 10, 0)))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_PROVINCES"),
                        List.of(Identifier.of("PROVINCE_ID"))))
                .build();
    }
}
