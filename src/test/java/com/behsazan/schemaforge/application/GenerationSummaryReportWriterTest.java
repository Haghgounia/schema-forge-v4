package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactLedger;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSummaryReportWriterTest {

    @Test
    void reportsExtractedExistingNewAndUnknownTablesWithoutGuessing() throws Exception {
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addTable(table("CUSTOMERS"))
                .addTable(table("ORDERS"))
                .addTable(table("PAYMENTS"))
                .build();
        ArtifactGenerationContext context = context();

        context.ledger().generated(context, ArtifactType.MIGRATION, DatabasePlatform.ORACLE,
                "APP.CUSTOMERS", "migration/oracle/customers.sql", "application/sql", "MigrationGenerationService");
        context.ledger().skipped(context, ArtifactType.MIGRATION, DatabasePlatform.ORACLE,
                "APP.ORDERS", "MigrationGenerationService",
                "LIVE_TABLE_NOT_FOUND: Live table was not found");
        context.ledger().skipped(context, ArtifactType.MIGRATION, DatabasePlatform.ORACLE,
                "APP.PAYMENTS", "MigrationGenerationService",
                "METADATA_UNAVAILABLE: Metadata connection became unavailable");

        for (Table table : schema.tables()) {
            context.ledger().skipped(context, ArtifactType.MIGRATION, DatabasePlatform.POSTGRESQL,
                    "APP." + table.qualifiedName().name().value(), "MigrationGenerationService",
                    "METADATA_UNAVAILABLE: Metadata repository is disabled or unavailable");
        }

        Path output = Files.createTempDirectory("schemaforge-summary-test-");
        try {
            Path written = new GenerationSummaryReportWriter(new ArtifactNamingPolicy()).write(
                    output, context, List.of(schema),
                    Set.of(DatabasePlatform.ORACLE, DatabasePlatform.POSTGRESQL),
                    Map.of("Input Documents", "1"), null);

            String report = Files.readString(written);
            assertTrue(report.contains("Request Type         : STANDARD_WORD"));
            assertTrue(report.contains("Request Status       : SUCCESS"));
            assertTrue(report.contains("Extracted Tables     : 3"));
            assertTrue(report.contains("APP.CUSTOMERS"));
            assertTrue(report.contains("APP.ORDERS"));
            assertTrue(report.contains("APP.PAYMENTS"));

            String oracle = between(report, "ORACLE\n------", "POSTGRESQL\n----------");
            assertTrue(oracle.contains("Metadata Status      : PARTIAL"));
            assertTrue(oracle.contains("Existing Tables      : 1"));
            assertTrue(oracle.contains("New Tables           : 1"));
            assertTrue(oracle.contains("Unknown Tables       : 1"));

            String postgres = report.substring(report.indexOf("POSTGRESQL\n----------"));
            assertTrue(postgres.contains("Metadata Status      : UNAVAILABLE"));
            assertTrue(postgres.contains("Existing Tables      : UNKNOWN"));
            assertTrue(postgres.contains("New Tables           : UNKNOWN"));
            assertTrue(postgres.contains("Classification       : NOT_PERFORMED"));
            assertTrue(postgres.contains("Reason               : METADATA_UNAVAILABLE"));

            var summaryArtifacts = context.ledger().snapshot().stream()
                    .filter(descriptor -> descriptor.type() == ArtifactType.SUMMARY_REPORT)
                    .filter(descriptor -> "generation-summary".equals(descriptor.logicalName()))
                    .toList();
            assertEquals(1, summaryArtifacts.size());
            assertEquals(ArtifactStatus.GENERATED, summaryArtifacts.getFirst().status());
            assertEquals("reports/schemaforge-generation-summary.txt", summaryArtifacts.getFirst().relativePath());
        } finally {
            new ArtifactPackageBuilder().deleteRecursively(output);
        }
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        return value.substring(from, to);
    }

    private static Table table(String name) {
        return Table.builder("APP", name)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .build();
    }

    private static ArtifactGenerationContext context() {
        return new ArtifactGenerationContext(
                "gen-summary-001",
                "20260909_210000_000",
                OffsetDateTime.parse("2026-09-09T21:00:00+03:30"),
                ArtifactOrigin.STANDARD_WORD,
                "sample.docx",
                new ArtifactLedger());
    }
}
