package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.*;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaConformanceCohortControllerTest {
    @Test void selectedCohortUsesOnlySelectedOracleSessionAndCarriesIdentityAndTableEvidence() throws Exception {
        var registry = new RuntimeMetadataConnectionRegistry();
        var connection = registry.register(DatabasePlatform.ORACLE,
                "jdbc:oracle:thin:@//example.invalid:1521/TEST", "auditor", "SECRET", "FEE3");
        var repository = registry.resolve(connection.token(), DatabasePlatform.ORACLE);
        var service = mock(SchemaConformanceAuditService.class);
        var report = new SchemaConformanceReport(SchemaConformanceReport.CONTRACT,
                DatabasePlatform.ORACLE, SchemaConformanceScope.TABLES, "FEE3", null,
                SchemaConformanceAuditService.TABLE_RULE_FAMILIES, List.of(),
                new SchemaConformanceSummary(2, 2, 0, 0, 0, 0, true), List.of());
        when(service.auditSelectedTables(DatabasePlatform.ORACLE, "FEE3", List.of("FCE_A", "FCE_B"), repository))
                .thenReturn(report);
        var mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service, registry)).build();
        String body = "{\"schema\":\"FEE3\",\"tables\":[\"FCE_A\",\"FCE_B\"]}";
        mvc.perform(post("/api/v1/conformance/selected/tables")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/conformance/selected/tables")
                        .header(RuntimeMetadataConnectionRegistry.HEADER, "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/conformance/selected/tables")
                        .header(RuntimeMetadataConnectionRegistry.HEADER, connection.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.platform").value("oracle"))
                .andExpect(jsonPath("$.target.verifiedSchema").value("FEE3"))
                .andExpect(jsonPath("$.target.token").doesNotExist())
                .andExpect(jsonPath("$.requestedTables.length()").value(2))
                .andExpect(jsonPath("$.requestedTables[0]").value("FCE_A"))
                .andExpect(jsonPath("$.report.scope").value("TABLES"))
                .andExpect(jsonPath("$.report.summary.tablesScanned").value(2));
        verify(service).auditSelectedTables(DatabasePlatform.ORACLE, "FEE3",
                List.of("FCE_A", "FCE_B"), repository);
        verifyNoMoreInteractions(service);
    }
}
