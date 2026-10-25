package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaConformanceCohortAuditServiceTest {
    private final MetadataRepositoryResolver global = mock(MetadataRepositoryResolver.class);
    private final SchemaConformanceAuditService service = new SchemaConformanceAuditService(global);

    private static Table table(String name) {
        return Table.builder("FEE3", name).addColumn(new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 19, 0), false,
                new DefaultValue(null), new Description(""), false, 1, null)).build();
    }

    @Test void readsExactlyTheSelectedCohortAndNeverFallsBack() {
        var selected = new InMemoryMetadataRepository(List.of(), List.of(
                table("FCE_A"), table("FCE_B"), table("NOT_SELECTED")));
        var report = service.auditSelectedTables(DatabasePlatform.ORACLE, "FEE3",
                List.of("FCE_A", "FCE_B", "FCE_A"), selected);
        assertEquals(SchemaConformanceScope.TABLES, report.scope());
        assertEquals("FEE3", report.schema());
        assertNull(report.table());
        assertEquals(2, report.summary().tablesScanned());
        assertEquals(2, report.summary().columnsScanned());
        assertEquals(8, report.ruleFamilySummaries().size());
        verifyNoInteractions(global);
    }

    @Test void rejectsMissingTablesInsteadOfReportingAnIncompleteSuccess() {
        var selected = new InMemoryMetadataRepository(List.of(), List.of(table("FCE_A")));
        var missing = assertThrows(IllegalArgumentException.class,
                () -> service.auditSelectedTables(DatabasePlatform.ORACLE, "FEE3",
                        List.of("FCE_A", "FCE_MISSING"), selected));
        assertTrue(missing.getMessage().contains("FCE_MISSING"));
        verifyNoInteractions(global);
    }

    @Test void emptyOversizedAndInvalidListsFailBeforeAccess() {
        var selected = mock(MetadataRepository.class);
        assertThrows(IllegalArgumentException.class, () -> service.auditSelectedTables(
                DatabasePlatform.ORACLE, "FEE3", List.of(), selected));
        assertThrows(IllegalArgumentException.class, () -> service.auditSelectedTables(
                DatabasePlatform.ORACLE, "FEE3", IntStream.range(0, 101).mapToObj(i -> "FCE_" + i).toList(), selected));
        assertThrows(IllegalArgumentException.class, () -> service.auditSelectedTables(
                DatabasePlatform.ORACLE, "FEE3", List.of("\r"), selected));
        assertThrows(IllegalArgumentException.class, () -> service.auditSelectedTables(
                DatabasePlatform.ORACLE, "FEE3", List.of("Foo", "foo"), selected));
        verifyNoInteractions(selected, global);
    }

    @Test void refusesObjectIdentityMismatchFromMetadata() {
        var selected = mock(MetadataRepository.class);
        when(selected.available()).thenReturn(true);
        when(selected.schemaExistenceAuthoritative()).thenReturn(false);
        when(selected.findTables(eq("FEE3"), anySet())).thenReturn(Map.of("FCE_A", Table.builder("OTHER", "FCE_A")
                .addColumn(new Column(Identifier.of("ID"), DataType.numeric("NUMBER", 19, 0), false,
                        new DefaultValue(null), new Description(""), false, 1, null)).build()));
        var invalid = assertThrows(IllegalArgumentException.class,
                () -> service.auditSelectedTables(DatabasePlatform.ORACLE, "FEE3", List.of("FCE_A"), selected));
        assertTrue(invalid.getMessage().contains("identity mismatch"));
        verifyNoInteractions(global);
    }
}
