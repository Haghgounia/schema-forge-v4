package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMetadataConnectionRegistryTest {
    @Test void selectionIsOpaquePerPlatformAndDoesNotRequireARealDatabaseUntilRepositoryQuery() {
        RuntimeMetadataConnectionRegistry registry = new RuntimeMetadataConnectionRegistry();
        var selection = registry.register(DatabasePlatform.ORACLE,
                "jdbc:oracle:thin:@//example.invalid:1521/TEST", "tester", "temporary-password", "APP");
        assertEquals("oracle", selection.platform());
        assertEquals("APP", selection.verifiedSchema());
        assertTrue(selection.token().length() >= 40);
        assertFalse(selection.toString().contains("temporary-password"));
        assertTrue(registry.resolve(selection.token(), DatabasePlatform.ORACLE).available());
        assertThrows(ResponseStatusException.class,
                () -> registry.resolve(selection.token(), DatabasePlatform.POSTGRESQL));
        assertEquals("", registry.describe(selection.token()).token());
        registry.disconnect(selection.token());
        assertThrows(ResponseStatusException.class,
                () -> registry.resolve(selection.token(), DatabasePlatform.ORACLE));
    }

    @Test void runtimeSelectionCanDeferSchemaWithoutChangingOtherUsers() {
        RuntimeMetadataConnectionRegistry registry = new RuntimeMetadataConnectionRegistry();
        var selection = registry.register(DatabasePlatform.POSTGRESQL,
                "jdbc:postgresql://example.invalid:5432/test", "reader", "password", "",
                "public", java.util.List.of("public", "bank"), false);
        assertEquals("", selection.verifiedSchema());
        assertEquals("public", selection.defaultSchema());
        assertEquals(java.util.List.of("public", "bank"), selection.availableSchemas());
        assertTrue(registry.resolve(selection.token(), DatabasePlatform.POSTGRESQL).available());
        assertEquals("public", registry.describe(selection.token()).defaultSchema());
        assertEquals("", registry.describe(selection.token()).token());
    }

    @Test void auditTargetIsBoundToTokenAndRedactsJdbcPropertiesAndPassword() {
        RuntimeMetadataConnectionRegistry registry = new RuntimeMetadataConnectionRegistry();
        var activated = registry.register(DatabasePlatform.SQLSERVER,
                "jdbc:sqlserver://example.invalid:1433;databaseName=finance;encrypt=true;trustServerCertificate=true",
                "reader", "DO_NOT_RETURN_THIS_PASSWORD", "finance", "dbo", java.util.List.of("dbo"), false,
                "finance", "SQL Server", "2022");
        var described = registry.describe(activated.token());
        assertEquals("", described.token());
        assertEquals("jdbc:sqlserver://example.invalid:1433", described.jdbcTarget());
        assertEquals("finance", described.databaseName());
        assertEquals("reader", described.username());
        assertEquals("SQL Server", described.databaseProduct());
        assertFalse(described.toString().contains("DO_NOT_RETURN_THIS_PASSWORD"));
        assertFalse(described.toString().contains("trustServerCertificate"));
    }

    @Test void sqlServerDatabaseNameIsAvailableWhenJdbcCatalogIsEmpty() {
        RuntimeMetadataConnectionRegistry registry = new RuntimeMetadataConnectionRegistry();
        var activated = registry.register(DatabasePlatform.SQLSERVER,
                "jdbc:sqlserver://example.invalid:1433;databaseName=finance;encrypt=true",
                "reader", "secret", "dbo");
        assertEquals("finance", registry.describe(activated.token()).databaseName());
        assertEquals("jdbc:sqlserver://example.invalid:1433", registry.describe(activated.token()).jdbcTarget());
    }

    @Test void unrecognizedTokensFailClosed() {
        RuntimeMetadataConnectionRegistry registry = new RuntimeMetadataConnectionRegistry();
        assertThrows(ResponseStatusException.class,
                () -> registry.resolve("unknown", DatabasePlatform.MYSQL));
    }
}
