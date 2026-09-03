package com.behsazan.schemaforge.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionAssemblyContractTest {

    @Test
    void assemblyAndVerificationScriptsArePresent() {
        Path scripts = root().resolve("distribution/scripts");
        assertTrue(Files.isRegularFile(scripts.resolve("assemble-distribution-windows.cmd")));
        assertTrue(Files.isRegularFile(scripts.resolve("verify-distribution-windows.cmd")));
        assertTrue(Files.isRegularFile(scripts.resolve("reproducible-ga-build-windows.cmd")));
    }

    @Test
    void assemblyRequiresReproduciblyFrozenGaBinary() throws IOException {
        String text = Files.readString(root().resolve("distribution/scripts/assemble-distribution-windows.cmd"));
        assertTrue(text.contains("set \"GA_JAR=schema-forge-v4-%PRODUCT_VERSION%.jar\""));
        assertTrue(text.contains("set \"SOURCE_JAR=target\\%GA_JAR%\""));
        assertTrue(text.contains("distribution\\checksums\\SHA256SUMS.txt"));
        assertTrue(text.contains("GA binary checksum mismatch"));
        assertTrue(text.contains("reproducible-ga-build-windows.cmd"));
        assertTrue(text.contains("exit /b 33"));
        assertFalse(text.contains("EXPECTED_GA_SHA256=780576"));
    }

    @Test
    void assemblyProducesOnlyRuntimeDistributionMaterial() throws IOException {
        String text = Files.readString(root().resolve("distribution/scripts/assemble-distribution-windows.cmd"));
        for (String required : List.of(
                "distribution\\config",
                "distribution\\docs",
                "distribution\\samples",
                "start-windows.cmd",
                "smoke-test-windows.cmd",
                "verify-checksum-windows.cmd",
                "schemaforge-v4-%PRODUCT_VERSION%-distribution.zip")) {
            assertTrue(text.contains(required), () -> "Assembly script missing: " + required);
        }
        for (String forbidden : List.of("src\\main", "src\\test", "mvnw.cmd", "pom.xml", "4.0.0-RC1")) {
            assertFalse(text.contains("copy /y \"" + forbidden), () -> "Assembly must not copy developer material: " + forbidden);
        }
    }

    @Test
    void projectRootVerifierChecksStageAndZipIntegrity() throws IOException {
        String text = Files.readString(root().resolve("distribution/scripts/verify-distribution-windows.cmd"));
        for (String required : List.of(
                "target\\distribution-stage\\schemaforge-v4-%PRODUCT_VERSION%",
                "bin\\%GA_JAR%",
                "checksums\\SHA256SUMS.txt",
                "schemaforge-v4-%PRODUCT_VERSION%-distribution.zip",
                "Distribution ZIP SHA-256 mismatch")) {
            assertTrue(text.contains(required), () -> "Verifier missing contract item: " + required);
        }
    }

    @Test
    void phase19_3ReleaseEvidenceDocumentsReproducibilityGate() throws IOException {
        Path evidence = root().resolve("docs/release/P19.3-DISTRIBUTION-ASSEMBLY-FINAL-SMOKE.md");
        assertTrue(Files.isRegularFile(evidence));
        String text = Files.readString(evidence);
        assertTrue(text.contains("project.build.outputTimestamp"));
        assertTrue(text.contains("reproducible-ga-build-windows.cmd"));
        assertTrue(text.contains("schemaforge-v4-4.0.0-distribution.zip"));
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
