package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifest;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartialSuccessPackageContractTest {

    @TempDir
    Path temp;

    @Test
    void partialSuccessMustStillProduceZipWithGeneratedArtifactsAndManifest()
            throws Exception {

        Path output = Files.createDirectories(temp.resolve("output"));

        ArtifactGenerationContext context =
                ArtifactGenerationContext.create(
                        ArtifactOrigin.STANDARD_WORD,
                        "sample.docx");

        Path oracleDdl =
                output.resolve("ddl/oracle/T.oracle.sql");

        Files.createDirectories(oracleDdl.getParent());

        Files.writeString(
                oracleDdl,
                "CREATE TABLE T (ID NUMBER);\n",
                StandardCharsets.UTF_8);

        context.ledger().generated(
                context,
                ArtifactType.DDL,
                DatabasePlatform.ORACLE,
                "T",
                "ddl/oracle/T.oracle.sql",
                "application/sql",
                "DdlGenerator");

        context.ledger().blocked(
                context,
                ArtifactType.DDL,
                DatabasePlatform.DB2_ZOS,
                "T",
                "DdlGenerator",
                "DB2_ZOS_DDL_BLOCKED: validation environment unavailable");

        ObjectMapper objectMapper = new ObjectMapper();

        ArtifactManifest manifest =
                new ArtifactManifestWriter(objectMapper).write(
                        output,
                        context,
                        "sample",
                        List.of(),
                        Map.of());

        assertEquals(
                ArtifactRequestStatus.PARTIAL_SUCCESS,
                manifest.requestStatus());

        ArtifactPackageBuilder packageBuilder =
                new ArtifactPackageBuilder();

        byte[] zipBytes =
                packageBuilder.zipDirectory(output);

        assertTrue(zipBytes.length > 0);

        Map<String, byte[]> entries =
                unzip(zipBytes);

        assertEquals(
                2,
                entries.size());

        assertTrue(
                entries.containsKey(
                        "ddl/oracle/T.oracle.sql"));

        assertTrue(
                entries.containsKey(
                        "manifest.json"));

        /*
         * A BLOCKED artifact has no physical file and therefore must not
         * appear as a ZIP entry.
         */
        assertFalse(
                entries.keySet().stream()
                        .anyMatch(name ->
                                name.toLowerCase()
                                        .contains("db2")));

        String oracleSql =
                new String(
                        entries.get(
                                "ddl/oracle/T.oracle.sql"),
                        StandardCharsets.UTF_8);

        assertEquals(
                "CREATE TABLE T (ID NUMBER);\n",
                oracleSql);

        JsonNode manifestJson =
                objectMapper.readTree(
                        entries.get("manifest.json"));

        assertEquals(
                "PARTIAL_SUCCESS",
                manifestJson.path(
                        "requestStatus").asText());

        assertEquals(
                2,
                manifestJson.path("artifactOutcomes")
                        .path("generated")
                        .asLong());

        assertEquals(
                1,
                manifestJson.path("artifactOutcomes")
                        .path("blocked")
                        .asLong());

        JsonNode blockedArtifact = null;

        for (JsonNode artifact :
                manifestJson.path("artifacts")) {

            if ("BLOCKED".equals(
                    artifact.path("status").asText())) {

                blockedArtifact = artifact;
                break;
            }
        }

        assertTrue(
                blockedArtifact != null);

        assertEquals(
                "DB2_ZOS",
                blockedArtifact.path(
                        "platform").asText());

        assertEquals(
                "DB2_ZOS_DDL_BLOCKED: validation environment unavailable",
                blockedArtifact.path(
                        "outcomeReason").asText());

        assertTrue(
                blockedArtifact.path(
                        "path").isNull()
                        || blockedArtifact.path(
                        "path").isMissingNode());

        assertTrue(
                blockedArtifact.path(
                        "integrity").isNull()
                        || blockedArtifact.path(
                        "integrity").isMissingNode());
    }

    private static Map<String, byte[]> unzip(
            byte[] zipBytes) throws Exception {

        Map<String, byte[]> entries =
                new LinkedHashMap<>();

        try (ZipInputStream zip =
                     new ZipInputStream(
                             new ByteArrayInputStream(
                                     zipBytes))) {

            var entry = zip.getNextEntry();

            while (entry != null) {

                entries.put(
                        entry.getName(),
                        zip.readAllBytes());

                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }

        return entries;
    }
}