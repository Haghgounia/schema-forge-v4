package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.OracleCrudGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OracleCrudControllerTest {

    @Test
    void returnsDownloadableSqlResponse() {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        when(service.generate("BIM", "PROVINCES"))
                .thenReturn(new OracleCrudGenerationService.OracleCrudGenerationResult(
                        "BIM.PROVINCES.oracle.crud-package.sql",
                        "CREATE OR REPLACE PACKAGE BIM.PKG_PROVINCES AS END;"));
        OracleCrudController controller = new OracleCrudController(service);

        var response = controller.generate(new OracleCrudRequest("BIM", "PROVINCES"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .contains("BIM.PROVINCES.oracle.crud-package.sql"));
        assertTrue(new String(response.getBody()).contains("PKG_PROVINCES"));
    }
}
