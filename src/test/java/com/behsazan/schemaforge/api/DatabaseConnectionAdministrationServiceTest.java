package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.config.MetadataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionAdministrationServiceTest {
    private static DatabaseConnectionAdministrationService service() {
        MetadataProperties configuration = new MetadataProperties();
        configuration.getOracle().setUrl("jdbc:oracle:thin:@//127.0.0.1:1521/TEST");
        configuration.getOracle().setUsername("tester");
        configuration.getOracle().setPassword("private-credential-not-for-json");
        return new DatabaseConnectionAdministrationService(configuration, "local-admin-test");
    }

    @Test void allSevenPlatformsExposeEffectiveDefaultsWithoutCredentials() {
        try (TestService tested = new TestService()) {
            var entries = tested.service.summaries();
            assertEquals(7, entries.size());
            var oracle = entries.stream().filter(item -> item.platform().equals("oracle")).findFirst().orElseThrow();
            assertEquals("jdbc:oracle:thin:@//127.0.0.1:1521/TEST", oracle.jdbcUrl());
            assertEquals("tester", oracle.username());
            assertTrue(oracle.passwordConfigured());
            assertFalse(entries.toString().contains("private-credential-not-for-json"));
            assertEquals(7, entries.stream().map(item -> item.platform()).distinct().count());
        }
    }

    @Test void bothReadAndProbeRequireExplicitSeparateAdministratorKey() {
        try (TestService tested = new TestService()) {
            assertThrows(ResponseStatusException.class, () -> tested.service.authorize(null));
            assertThrows(ResponseStatusException.class, () -> tested.service.authorize("invalid"));
            tested.service.authorize("local-admin-test");
        }
        var missing = new DatabaseConnectionAdministrationService(new MetadataProperties(), "");
        try {
            assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> missing.authorize("anything")).getStatusCode().value());
        } finally { missing.shutdown(); }
    }

    @Test void configuredPasswordCannotBeForwardedToDifferentHostOrUser() {
        try (TestService tested = new TestService()) {
            assertThrows(IllegalArgumentException.class, () -> tested.service.probe(
                    new DatabaseConnectionAdministrationService.ProbeRequest(
                            "oracle", "jdbc:oracle:thin:@//other-host:1521/TEST", "tester", "", true)));
            assertThrows(IllegalArgumentException.class, () -> tested.service.probe(
                    new DatabaseConnectionAdministrationService.ProbeRequest(
                            "oracle", "jdbc:oracle:thin:@//127.0.0.1:1521/TEST", "another", "", true)));
        }
    }

    @Test void rejectMismatchedDriversAndEmbeddedUrlCredentials() {
        try (TestService tested = new TestService()) {
            assertThrows(IllegalArgumentException.class, () -> tested.service.probe(
                    new DatabaseConnectionAdministrationService.ProbeRequest(
                            "postgresql", "jdbc:mysql://localhost:3306/app", "tester", "", false)));
            assertThrows(IllegalArgumentException.class, () -> tested.service.probe(
                    new DatabaseConnectionAdministrationService.ProbeRequest(
                            "mysql", "jdbc:mysql://localhost:3306/test?password=exposed", "tester", "", false)));
            assertThrows(IllegalArgumentException.class, () -> tested.service.probe(
                    new DatabaseConnectionAdministrationService.ProbeRequest(
                            "h2", "jdbc:h2:mem:test", "sa", "", false)));
        }
    }

    private static final class TestService implements AutoCloseable {
        final DatabaseConnectionAdministrationService service = service();
        @Override public void close() { service.shutdown(); }
    }
}
