package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.config.MetadataProperties;
import org.junit.jupiter.api.Test;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionAdministrationServiceTest {
    private static DatabaseConnectionAdministrationService service() {
        MetadataProperties configuration = new MetadataProperties();
        configuration.getOracle().setUrl("jdbc:oracle:thin:@//127.0.0.1:1521/TEST");
        configuration.getOracle().setUsername("tester");
        configuration.getOracle().setPassword("private-credential-not-for-json");
        return new DatabaseConnectionAdministrationService(configuration, new RuntimeMetadataConnectionRegistry());
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

    @Test void selfServiceDoesNotRequireAnAdministratorKey() {
        try (TestService tested = new TestService()) {
            assertEquals(7, tested.service.summaries().size());
            // A missing schema must no longer be rejected at input-validation time.
            // The service still rejects null connection input, without any admin key.
            assertThrows(IllegalArgumentException.class, () -> tested.service.activate(null));
        }
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


    @Test void blankSchemaActivatesOnlyAfterRealJdbcCatalogEnumeration() throws Exception {
        // The test driver does not talk to an actual DBMS and cannot modify user databases.
        var driver = new InProcessSchemaDriver();
        java.sql.DriverManager.registerDriver(driver);
        var runtime = new RuntimeMetadataConnectionRegistry();
        var service = new DatabaseConnectionAdministrationService(new MetadataProperties(), runtime);
        try {
            var input = new DatabaseConnectionAdministrationService.ProbeRequest(
                    "postgresql", "jdbc:postgresql://127.0.0.1:1/schemaforge-mock", "reader", "dummy", false, "");
            var probe = service.probe(input);
            assertTrue(probe.success(), probe.code());
            assertEquals("METADATA_DISCOVERED", probe.code());
            assertEquals("public", probe.defaultSchema());
            assertEquals(java.util.List.of("business", "public"), probe.availableSchemas());
            var selected = service.activate(input);
            assertEquals("", selected.verifiedSchema());
            assertEquals("public", selected.defaultSchema());
            assertTrue(selected.token().length() >= 40);
            assertEquals("", runtime.describe(selected.token()).verifiedSchema());
        } finally {
            service.shutdown();
            java.sql.DriverManager.deregisterDriver(driver);
        }
    }

    @Test void configuredProfileActivatesWithServerOnlyCredentialsAndNoBrowserPassword() throws Exception {
        var driver = new InProcessSchemaDriver();
        java.sql.DriverManager.registerDriver(driver);
        MetadataProperties config = new MetadataProperties();
        config.getPostgresql().setEnabled(true);
        config.getPostgresql().setUrl("jdbc:postgresql://127.0.0.1:1/schemaforge-mock");
        config.getPostgresql().setUsername("reader");
        config.getPostgresql().setPassword("server-secret-must-not-leak");
        var registry = new RuntimeMetadataConnectionRegistry();
        var controller = new DatabaseConnectionAdministrationService(config, registry);
        try {
            var selected = controller.activateConfigured("postgresql");
            assertEquals("postgresql", selected.platform());
            assertEquals("reader", selected.username());
            assertEquals(java.util.List.of("business", "public"), selected.availableSchemas());
            assertFalse(selected.toString().contains("server-secret-must-not-leak"));
            assertNotEquals("", selected.token());
        } finally {
            controller.shutdown();
            java.sql.DriverManager.deregisterDriver(driver);
        }
    }

    @Test void disabledConfiguredConnectionsFailBeforeAnyJdbcAccess() {
        var controller = new DatabaseConnectionAdministrationService(
                new MetadataProperties(), new RuntimeMetadataConnectionRegistry());
        try {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> controller.activateConfigured("oracle"));
        } finally {controller.shutdown();}
    }

    private static final class InProcessSchemaDriver implements java.sql.Driver {
        @Override public java.sql.Connection connect(String url, java.util.Properties info)
                throws java.sql.SQLException {
            if (!acceptsURL(url)) return null;
            var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
            var metadata = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
            var rows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metadata);
            org.mockito.Mockito.when(connection.getSchema()).thenReturn("public");
            org.mockito.Mockito.when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL test fixture");
            org.mockito.Mockito.when(metadata.getDatabaseProductVersion()).thenReturn("fixture");
            org.mockito.Mockito.when(metadata.getSchemas()).thenReturn(rows);
            org.mockito.Mockito.when(rows.next()).thenReturn(true, true, false);
            org.mockito.Mockito.when(rows.getString("TABLE_SCHEM")).thenReturn("public", "business");
            return connection;
        }
        @Override public boolean acceptsURL(String url) {
            return "jdbc:postgresql://127.0.0.1:1/schemaforge-mock".equals(url);
        }
        @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }

    private static final class TestService implements AutoCloseable {
        final DatabaseConnectionAdministrationService service = service();
        @Override public void close() { service.shutdown(); }
    }
}
