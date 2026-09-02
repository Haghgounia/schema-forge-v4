package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import com.behsazan.schemaforge.conformance.SchemaConformanceSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaConformanceControllerTest {

    @Test
    void exposesReadOnlyTableAuditEndpoint() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMERS"))
                .thenReturn(new SchemaConformanceReport(
                        SchemaConformanceReport.CONTRACT,
                        DatabasePlatform.ORACLE,
                        SchemaConformanceScope.TABLE,
                        "TSTSHMA",
                        "CUSTOMERS",
                        List.of("STRUCTURAL"),
                        List.of(),
                        new SchemaConformanceSummary(1, 2, 0, 0, 0, 0, true),
                        List.of()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service)).build();
        mvc.perform(get("/api/v1/conformance/table")
                        .param("platform", "oracle")
                        .param("schema", "TSTSHMA")
                        .param("table", "CUSTOMERS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportContract").value(SchemaConformanceReport.CONTRACT))
                .andExpect(jsonPath("$.platform").value("ORACLE"))
                .andExpect(jsonPath("$.scope").value("TABLE"))
                .andExpect(jsonPath("$.summary.tablesScanned").value(1))
                .andExpect(jsonPath("$.ruleFamilySummaries").isArray())
                .andExpect(jsonPath("$.findings").isArray());
    }
    @Test
    void exposesReadOnlySchemaAuditEndpoint() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditSchema(DatabasePlatform.ORACLE, "TSTSHMA"))
                .thenReturn(new SchemaConformanceReport(
                        SchemaConformanceReport.CONTRACT,
                        DatabasePlatform.ORACLE,
                        SchemaConformanceScope.SCHEMA,
                        "TSTSHMA",
                        null,
                        List.of("STRUCTURAL"),
                        List.of(),
                        new SchemaConformanceSummary(2, 4, 0, 1, 0, 1, false),
                        List.of()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service)).build();
        mvc.perform(get("/api/v1/conformance/schema")
                        .param("platform", "oracle")
                        .param("schema", "TSTSHMA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SCHEMA"))
                .andExpect(jsonPath("$.summary.tablesScanned").value(2));
    }

}
