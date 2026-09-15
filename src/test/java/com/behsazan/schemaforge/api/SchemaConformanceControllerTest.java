package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceMultiPlatformReport;
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
    void exposesReadOnlyTableAuditEndpointAndPreservesSinglePlatformResponse() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditTable(DatabasePlatform.ORACLE, "TSTSHMA", "CUSTOMERS"))
                .thenReturn(report(DatabasePlatform.ORACLE, SchemaConformanceScope.TABLE, "TSTSHMA", "CUSTOMERS", 1));

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
    void exposesReadOnlySchemaAuditEndpointAndPreservesSinglePlatformResponse() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditSchema(DatabasePlatform.ORACLE, "TSTSHMA"))
                .thenReturn(report(DatabasePlatform.ORACLE, SchemaConformanceScope.SCHEMA, "TSTSHMA", null, 2));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service)).build();
        mvc.perform(get("/api/v1/conformance/schema")
                        .param("platform", "oracle")
                        .param("schema", "TSTSHMA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportContract").value(SchemaConformanceReport.CONTRACT))
                .andExpect(jsonPath("$.scope").value("SCHEMA"))
                .andExpect(jsonPath("$.summary.tablesScanned").value(2));
    }

    @Test
    void returnsAggregateReportWhenMultiplePlatformsAreSelected() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditSchema(DatabasePlatform.ORACLE, "BANKING"))
                .thenReturn(report(DatabasePlatform.ORACLE, SchemaConformanceScope.SCHEMA, "BANKING", null, 2));
        when(service.auditSchema(DatabasePlatform.POSTGRESQL, "BANKING"))
                .thenReturn(report(DatabasePlatform.POSTGRESQL, SchemaConformanceScope.SCHEMA, "BANKING", null, 3));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service)).build();
        mvc.perform(get("/api/v1/conformance/schema")
                        .param("platform", "oracle", "postgresql")
                        .param("schema", "BANKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportContract").value(SchemaConformanceMultiPlatformReport.CONTRACT))
                .andExpect(jsonPath("$.scope").value("SCHEMA"))
                .andExpect(jsonPath("$.platforms.length()").value(2))
                .andExpect(jsonPath("$.platforms[0]").value("ORACLE"))
                .andExpect(jsonPath("$.platforms[1]").value("POSTGRESQL"))
                .andExpect(jsonPath("$.reports.length()").value(2))
                .andExpect(jsonPath("$.reports[0].summary.tablesScanned").value(2))
                .andExpect(jsonPath("$.reports[1].summary.tablesScanned").value(3));
    }

    @Test
    void acceptsCommaSeparatedMultiPlatformSelection() throws Exception {
        SchemaConformanceAuditService service = mock(SchemaConformanceAuditService.class);
        when(service.auditTable(DatabasePlatform.MYSQL, "BANKING", "CUSTOMER"))
                .thenReturn(report(DatabasePlatform.MYSQL, SchemaConformanceScope.TABLE, "BANKING", "CUSTOMER", 1));
        when(service.auditTable(DatabasePlatform.MARIADB, "BANKING", "CUSTOMER"))
                .thenReturn(report(DatabasePlatform.MARIADB, SchemaConformanceScope.TABLE, "BANKING", "CUSTOMER", 1));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service)).build();
        mvc.perform(get("/api/v1/conformance/table")
                        .param("platform", "mysql,mariadb")
                        .param("schema", "BANKING")
                        .param("table", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportContract").value(SchemaConformanceMultiPlatformReport.CONTRACT))
                .andExpect(jsonPath("$.platforms[0]").value("MYSQL"))
                .andExpect(jsonPath("$.platforms[1]").value("MARIADB"));
    }

    private static SchemaConformanceReport report(
            DatabasePlatform platform,
            SchemaConformanceScope scope,
            String schema,
            String table,
            int tablesScanned) {
        return new SchemaConformanceReport(
                SchemaConformanceReport.CONTRACT,
                platform,
                scope,
                schema,
                table,
                List.of("STRUCTURAL"),
                List.of(),
                new SchemaConformanceSummary(tablesScanned, 2, 0, 0, 0, 0, true),
                List.of());
    }
}
