package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OracleCrudGenerationService;
import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A selected CRUD request must not consult server JDBC fallback on invalid token/platform. */
class SelectedCrudConnectionGuardTest {
    @Test
    void rejectsUnknownTokenAndCrossPlatformSelectionBeforeRunningGenerator() {
        var registry = new RuntimeMetadataConnectionRegistry();
        var oracle = mock(OracleCrudGenerationService.class);
        var sqlServer = mock(SqlServerCrudGenerationService.class);
        var controller = new SelectedCrudController(registry, oracle, sqlServer);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.oracle("unregistered-token", new OracleCrudRequest("APP", "CUSTOMERS")));
        verifyNoInteractions(oracle, sqlServer);
        var sqlServerConnection = registry.register(DatabasePlatform.SQLSERVER,
                "jdbc:sqlserver://localhost:1433;databaseName=sample", "testuser", "testpassword", "APP");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.oracle(sqlServerConnection.token(), new OracleCrudRequest("APP", "CUSTOMERS")));
        verifyNoInteractions(oracle, sqlServer);
    }
}
