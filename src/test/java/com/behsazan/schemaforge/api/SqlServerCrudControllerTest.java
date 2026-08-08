package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Verifies the HTTP response contract of {@link SqlServerCrudController} without database access.
 *
 * <p>The generation service is mocked so the test can focus on status, attachment naming and
 * SQL Server procedure content.</p>
 */
class SqlServerCrudControllerTest {

    @Test
    void returnsDownloadableSqlResponse() {
        SqlServerCrudGenerationService service = mock(SqlServerCrudGenerationService.class);
        when(service.generate("BIM", "PROVINCES"))
                .thenReturn(new SqlServerCrudGenerationService.SqlServerCrudGenerationResult(
                        "BIM.PROVINCES_20260802_101112_345.sqlserver.crud-procedures.sql",
                        "CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_CREATE] AS BEGIN RETURN; END;"));
        SqlServerCrudController controller = new SqlServerCrudController(service);

        var response = controller.generate(new SqlServerCrudRequest("BIM", "PROVINCES"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .contains("BIM.PROVINCES_20260802_101112_345.sqlserver.crud-procedures.sql"));
        assertTrue(new String(response.getBody()).contains("PROVINCES_CREATE"));
    }
}
