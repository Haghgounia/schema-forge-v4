package com.behsazan.schemaforge.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionStructureContractTest {

    private static final String GA_JAR = "schema-forge-v4-4.0.0.jar";

    @Test
    void runtimeDistributionLayoutIsPresent() {
        Path root = locateProjectRoot().resolve("distribution");

        List<String> required = List.of(
                "README.md",
                "bin/README.md",
                "config/application.yml",
                "config/application-example.yml",
                "scripts/start-windows.cmd",
                "scripts/smoke-test-windows.cmd",
                "scripts/verify-checksum-windows.cmd",
                "scripts/reproducible-ga-build-windows.cmd",
                "docs/README.md",
                "samples/README.md",
                "checksums/SHA256SUMS.txt");

        for (String relative : required) {
            assertTrue(Files.isRegularFile(root.resolve(relative)), () -> "Missing distribution file: " + relative);
        }
    }

    @Test
    void externalRuntimeConfigurationIsSafeByDefault() throws IOException {
        Path config = locateProjectRoot().resolve("distribution/config/application.yml");
        String text = Files.readString(config);

        for (String dbms : List.of("ORACLE", "POSTGRESQL", "DB2ZOS", "DB2LUW", "SQLSERVER", "MYSQL")) {
            assertTrue(text.contains("${SCHEMAFORGE_METADATA_" + dbms + "_ENABLED:false}"),
                    () -> dbms + " metadata must be disabled by default in the distribution template");
            assertTrue(text.contains("${SCHEMAFORGE_METADATA_" + dbms + "_PASSWORD:}"),
                    () -> dbms + " password must come from external configuration");
        }

        for (String forbidden : List.of("Oracle123", "sa@123456", "mysql123", "password: 123456", "password: Schemaforge123")) {
            assertFalse(text.contains(forbidden), () -> "Distribution config contains development credential: " + forbidden);
        }
    }

    @Test
    void startScriptForcesExternalConfiguration() throws IOException {
        Path script = locateProjectRoot().resolve("distribution/scripts/start-windows.cmd");
        String text = Files.readString(script);

        assertTrue(text.contains(GA_JAR));
        assertTrue(text.contains("--spring.config.location=file:./config/application.yml"));
    }

    @Test
    void gaBinaryChecksumFileUsesDistributionContract() throws IOException {
        Path checksum = locateProjectRoot().resolve("distribution/checksums/SHA256SUMS.txt");
        String text = Files.readString(checksum).trim();

        assertTrue(text.matches("(?i)^[0-9a-f]{64}  bin/" + GA_JAR.replace(".", "\\.") + "$"),
                () -> "Unexpected GA checksum contract: " + text);
    }

    private static Path locateProjectRoot() {
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
