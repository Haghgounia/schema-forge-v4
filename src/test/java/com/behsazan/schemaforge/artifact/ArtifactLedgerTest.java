package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactLedgerTest {

    @Test
    void oneRequestSharesGenerationIdAcrossChildContexts() {
        ArtifactGenerationContext request = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "batch.zip");
        ArtifactGenerationContext child = request.child(ArtifactOrigin.ZIP_BATCH, "tables/customer.docx");

        assertEquals(request.generationId(), child.generationId());
        assertSame(request.ledger(), child.ledger());
    }

    @Test
    void isolatedChildPreservesGenerationIdButUsesTemporaryLedger() {
        ArtifactGenerationContext request = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "batch.zip");
        ArtifactGenerationContext child = request.isolatedChild(
                ArtifactOrigin.ZIP_BATCH, "tables/customer.docx");

        assertEquals(request.generationId(), child.generationId());
        assertNotEquals(request.ledger(), child.ledger());
    }

    @Test
    void ledgerRecordsGeneratedAndSkippedOutcomes() {
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD, "customer.docx");
        context.ledger().generated(context, ArtifactType.DDL, DatabasePlatform.ORACLE,
                "customer", "customer.oracle.sql", "application/sql", "DdlGenerator");
        context.ledger().skipped(context, ArtifactType.CRUD, DatabasePlatform.SQLSERVER,
                "BIM.CUSTOMER", "SqlServerCrudProcedureGenerator");

        var artifacts = context.ledger().snapshot();
        assertEquals(2, artifacts.size());
        assertEquals(ArtifactStatus.GENERATED, artifacts.get(0).status());
        assertEquals(ArtifactStatus.SKIPPED, artifacts.get(1).status());
        assertEquals(context.generationId(), artifacts.get(0).generationId());
        assertEquals("customer.docx", artifacts.get(0).provenance().sourceName());
    }

    @Test
    void artifactPathsArePortableAndPackageRelative() {
        String path = ArtifactPaths.relative(
                Path.of("output"), Path.of("output", "migration", "oracle", "V1__A.sql"));

        assertEquals("migration/oracle/V1__A.sql", path);
        assertTrue(path.indexOf('\\') < 0);
    }
}
