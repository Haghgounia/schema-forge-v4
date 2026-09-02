package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.ServiceUnavailableException;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaConformanceAuditServiceTest {

    @Test
    void auditsExistingTableReadOnlyAndReturnsFindings() {
        Table table = Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(new Column(
                        Identifier.of("PROFILEID"),
                        DataType.simple("NUMBER"),
                        true,
                        new DefaultValue("20"),
                        new Description(""),
                        false,
                        1,
                        null))
                .build();

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of(table)));

        SchemaConformanceReport report = new SchemaConformanceAuditService(resolver)
                .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMER");

        assertEquals(SchemaConformanceReport.CONTRACT, report.reportContract());
        assertEquals(SchemaConformanceScope.TABLE, report.scope());
        assertEquals(1, report.summary().tablesScanned());
        assertEquals(1, report.summary().columnsScanned());
        assertFalse(report.summary().compliant());
        assertTrue(report.findings().stream()
                .anyMatch(issue -> "TABLE_NAME_NOT_PLURAL".equals(issue.code())
                        && SchemaConformanceAuditService.METADATA_CONVENTION.equals(issue.ruleFamily())));
        assertTrue(report.findings().stream()
                .anyMatch(issue -> "NUMERIC_PRECISION_UNSPECIFIED".equals(issue.code())
                        && SchemaConformanceAuditService.DATATYPE_COMPATIBILITY.equals(issue.ruleFamily())));
        assertEquals(SchemaConformanceAuditService.TABLE_RULE_FAMILIES.size(),
                report.ruleFamilySummaries().size());
    }

    @Test
    void missingTableIsRejected() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new SchemaConformanceAuditService(resolver)
                        .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "MISSING_TABLE"));
        assertTrue(error.getMessage().contains("Live table was not found"));
    }
    @Test
    void auditsWholeSchemaReadOnly() {
        Table customers = Table.builder("TSTSHMA", "CUSTOMERS")
                .addColumn(new Column(
                        Identifier.of("CUSTOMER_ID"),
                        DataType.numeric("NUMBER", 12, 0),
                        false,
                        new DefaultValue(null),
                        new Description(""),
                        false,
                        1,
                        null))
                .build();
        Table payments = Table.builder("TSTSHMA", "PAYMENTS")
                .addColumn(new Column(
                        Identifier.of("AMOUNT"),
                        DataType.numeric("NUMBER", 18, 2),
                        false,
                        new DefaultValue("0"),
                        new Description(""),
                        false,
                        1,
                        null))
                .build();

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of(customers, payments)));

        SchemaConformanceReport report = new SchemaConformanceAuditService(resolver)
                .auditSchema(DatabasePlatform.ORACLE, "TSTSHMA");

        assertEquals(SchemaConformanceScope.SCHEMA, report.scope());
        assertEquals(2, report.summary().tablesScanned());
        assertEquals(2, report.summary().columnsScanned());
    }

    @Test
    void reportsInvalidCheckColumnUnderConstraintReferenceFamily() {
        Table table = Table.builder("TSTSHMA", "CUSTOMERS")
                .addColumn(new Column(
                        Identifier.of("STATUS"),
                        DataType.numeric("NUMBER", 1, 0),
                        false,
                        new DefaultValue(null),
                        new Description(""),
                        false,
                        1,
                        null))
                .addCheck(new CheckConstraint(
                        Identifier.of("CHK_CUSTOMERS_STATUS"),
                        "MISSING_STATUS IN (0,1)"))
                .build();

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of(table)));

        SchemaConformanceReport report = new SchemaConformanceAuditService(resolver)
                .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMERS");

        assertTrue(report.findings().stream().anyMatch(finding ->
                SchemaConformanceAuditService.CONSTRAINT_REFERENCES.equals(finding.ruleFamily())
                        && "CHECK_UNKNOWN_COLUMN".equals(finding.code())
                        && "ERROR".equals(finding.severity())));
        assertTrue(report.ruleFamilySummaries().stream().anyMatch(summary ->
                SchemaConformanceAuditService.CONSTRAINT_REFERENCES.equals(summary.ruleFamily())
                        && summary.errorCount() == 1));
    }


    @Test
    void reportsMissingPrimaryKeyUnderKeyConstraintFamily() {
        Table table = Table.builder("TSTSHMA", "CUSTOMERS")
                .addColumn(new Column(
                        Identifier.of("CUSTOMER_ID"),
                        DataType.numeric("NUMBER", 12, 0),
                        false,
                        new DefaultValue(null),
                        new Description(""),
                        false,
                        1,
                        null))
                .build();

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of(table)));

        SchemaConformanceReport report = new SchemaConformanceAuditService(resolver)
                .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMERS");

        assertTrue(report.findings().stream().anyMatch(finding ->
                SchemaConformanceAuditService.KEY_CONSTRAINTS.equals(finding.ruleFamily())
                        && "TABLE_PRIMARY_KEY_MISSING".equals(finding.code())
                        && "WARNING".equals(finding.severity())));
    }

    @Test
    void validatesForeignKeyTargetColumnsAndSupportingIndexCoverage() {
        Column parentId = new Column(
                Identifier.of("PARENT_ID"), DataType.numeric("NUMBER", 12, 0), false,
                new DefaultValue(null), new Description(""), false, 1, null);
        Table parents = Table.builder("TSTSHMA", "PARENTS")
                .addColumn(parentId)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_PARENTS"), List.of(Identifier.of("PARENT_ID"))))
                .build();

        Table children = Table.builder("TSTSHMA", "CHILDREN")
                .addColumn(new Column(
                        Identifier.of("CHILD_ID"), DataType.numeric("NUMBER", 12, 0), false,
                        new DefaultValue(null), new Description(""), false, 1, null))
                .addColumn(new Column(
                        Identifier.of("PARENT_ID"), DataType.numeric("NUMBER", 12, 0), false,
                        new DefaultValue(null), new Description(""), false, 2, null))
                .addColumn(new Column(
                        Identifier.of("BROKEN_PARENT_ID"), DataType.numeric("NUMBER", 12, 0), true,
                        new DefaultValue(null), new Description(""), false, 3, null))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_CHILDREN"), List.of(Identifier.of("CHILD_ID"))))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_CHILDREN_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of("TSTSHMA", "PARENTS"),
                        List.of(Identifier.of("PARENT_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_CHILDREN_BROKEN"),
                        List.of(Identifier.of("BROKEN_PARENT_ID")),
                        QualifiedName.of("TSTSHMA", "PARENTS"),
                        List.of(Identifier.of("MISSING_PARENT_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(new InMemoryMetadataRepository(List.of(), List.of(parents, children)));

        SchemaConformanceReport report = new SchemaConformanceAuditService(resolver)
                .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CHILDREN");

        assertTrue(report.findings().stream().anyMatch(finding ->
                SchemaConformanceAuditService.REFERENTIAL_INTEGRITY.equals(finding.ruleFamily())
                        && "FK_REFERENCED_COLUMN_NOT_FOUND".equals(finding.code())
                        && "ERROR".equals(finding.severity())));
        assertTrue(report.findings().stream().anyMatch(finding ->
                SchemaConformanceAuditService.INDEX_COVERAGE.equals(finding.ruleFamily())
                        && "PHYS-FK-INDEX-001".equals(finding.code())
                        && "INFO".equals(finding.severity())));
        assertTrue(report.ruleFamilySummaries().stream().anyMatch(summary ->
                SchemaConformanceAuditService.REFERENTIAL_INTEGRITY.equals(summary.ruleFamily())
                        && summary.errorCount() == 1));
        assertTrue(report.ruleFamilySummaries().stream().anyMatch(summary ->
                SchemaConformanceAuditService.INDEX_COVERAGE.equals(summary.ruleFamily())
                        && summary.infoCount() >= 1));
    }

    @Test
    void unavailableMetadataRepositoryIsServiceUnavailable() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE))
                .thenReturn(com.behsazan.schemaforge.metadata.repository.MetadataRepository.empty());

        assertThrows(
                ServiceUnavailableException.class,
                () -> new SchemaConformanceAuditService(resolver)
                        .auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMERS"));
    }

}
