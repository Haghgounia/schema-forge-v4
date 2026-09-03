package com.behsazan.schemaforge.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionReproducibleBuildContractTest {

    @Test
    void mavenBuildPinsOutputTimestamp() throws IOException {
        String pom = Files.readString(root().resolve("pom.xml"));
        assertTrue(pom.contains("<project.build.outputTimestamp>2026-09-02T00:00:00Z</project.build.outputTimestamp>"));
    }

    @Test
    void reproducibilityScriptPerformsTwoCleanPackageBuilds() throws IOException {
        String text = Files.readString(root().resolve("distribution/scripts/reproducible-ga-build-windows.cmd"));
        String command = "call mvnw.cmd clean package -DskipTests";
        int first = text.indexOf(command);
        int second = text.indexOf(command, first + command.length());
        assertTrue(first >= 0 && second > first, "Expected two clean package builds");
        assertTrue(text.contains("GA build is still not byte-for-byte reproducible"));
    }

    @Test
    void reproducibilityScriptFreezesChecksumOnlyAfterHashesMatch() throws IOException {
        String text = Files.readString(root().resolve("distribution/scripts/reproducible-ga-build-windows.cmd"));
        int comparison = text.indexOf("if /I not \"!HASH1!\"==\"!HASH2!\"");
        int write = text.indexOf("SHA256SUMS.txt");
        int redirect = text.lastIndexOf("bin/%GA_JAR%");
        assertTrue(comparison >= 0);
        assertTrue(write >= 0);
        assertTrue(redirect > comparison, "Checksum must be frozen only after reproducibility comparison");
    }

    @Test
    void historicalHashIsNotHardCodedIntoAssemblyGate() throws IOException {
        String assembly = Files.readString(root().resolve("distribution/scripts/assemble-distribution-windows.cmd"));
        assertFalse(assembly.contains("78057619993e942f0a43fb799da754b95282f365b4f6bab09210c86233f6db57"));
        String evidence = Files.readString(root().resolve("distribution/docs/VALIDATION-EVIDENCE-4.0.0.md"));
        assertTrue(evidence.contains("Historical pre-reproducibility GA artifact SHA-256"));
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
