package com.behsazan.schemaforge.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionDocumentationContractTest {

    @Test
    void requiredOperatorDocumentationIsPresent() {
        Path docs = root().resolve("distribution/docs");
        for (String file : List.of(
                "README.md",
                "INSTALLATION.md",
                "CONFIGURATION.md",
                "API-GUIDE.md",
                "DBA-GUIDE.md",
                "OPERATIONS-GUIDE.md",
                "RELEASE-NOTES-4.0.0.md",
                "KNOWN-LIMITATIONS-4.0.0.md",
                "VALIDATION-EVIDENCE-4.0.0.md")) {
            assertTrue(Files.isRegularFile(docs.resolve(file)), () -> "Missing distribution document: " + file);
        }
    }

    @Test
    void apiGuideTracksFrozenContracts() throws IOException {
        String api = Files.readString(root().resolve("distribution/docs/API-GUIDE.md"));
        for (String required : List.of(
                "/api/v1/generate/word",
                "/api/v1/generate/legacy-word",
                "/api/v1/generate/zip",
                "/api/v1/generate/ea-xml",
                "/api/v1/conformance/table",
                "/api/v1/conformance/schema",
                "/api/v1/generate/oracle/crud",
                "/api/v1/generate/sqlserver/crud",
                "/api/v1/diagram/mermaid/canonical-json",
                "schemaforge-schema-conformance/v3",
                "schemaforge-rest-error/v1",
                "X-SchemaForge-Request-Id")) {
            assertTrue(api.contains(required), () -> "API guide missing frozen contract item: " + required);
        }
    }

    @Test
    void configurationGuideDocumentsAllMetadataProfiles() throws IOException {
        String config = Files.readString(root().resolve("distribution/docs/CONFIGURATION.md"));
        for (String dbms : List.of("ORACLE", "POSTGRESQL", "DB2ZOS", "DB2LUW", "SQLSERVER", "MYSQL")) {
            assertTrue(config.contains("SCHEMAFORGE_METADATA_" + dbms + "_ENABLED"),
                    () -> "Missing enabled property for " + dbms);
            assertTrue(config.contains("SCHEMAFORGE_METADATA_" + dbms + "_PASSWORD"),
                    () -> "Missing password externalization for " + dbms);
        }
    }

    @Test
    void operationsDocsPreserveSafeLauncherAndGaChecksum() throws IOException {
        String install = Files.readString(root().resolve("distribution/docs/INSTALLATION.md"));
        String operations = Files.readString(root().resolve("distribution/docs/OPERATIONS-GUIDE.md"));
        String limitations = Files.readString(root().resolve("distribution/docs/KNOWN-LIMITATIONS-4.0.0.md"));
        String combined = install + operations + limitations;

        assertTrue(combined.contains("scripts\\start-windows.cmd"));
        assertTrue(combined.contains("--spring.config.location=file:./config/application.yml"));
        assertTrue(combined.contains("78057619993e942f0a43fb799da754b95282f365b4f6bab09210c86233f6db57"));
        assertTrue(limitations.contains("IBM JCC"));
        assertTrue(limitations.contains("development `application.yml`"));
    }

    @Test
    void distributionDocumentationDoesNotCopyKnownDevelopmentPasswords() throws IOException {
        Path distribution = root().resolve("distribution");
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(distribution)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                text.append(Files.readString(file)).append('\n');
            }
        }
        for (String forbidden : List.of("Oracle123", "sa@123456", "mysql123", "password: 123456", "Schemaforge123")) {
            assertFalse(text.toString().contains(forbidden), () -> "Distribution documentation contains development password: " + forbidden);
        }
    }

    @Test
    void safeRuntimeSamplesArePresent() {
        Path samples = root().resolve("distribution/samples/api");
        for (String file : List.of(
                "openapi-smoke.cmd",
                "sqlserver-conformance-schema.cmd",
                "sqlserver-crud-example.cmd")) {
            assertTrue(Files.isRegularFile(samples.resolve(file)), () -> "Missing runtime sample: " + file);
        }
    }

    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate SchemaForge project root containing pom.xml");
    }
}
