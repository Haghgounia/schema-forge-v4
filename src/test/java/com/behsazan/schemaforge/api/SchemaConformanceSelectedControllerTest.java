package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceRuleCatalog;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import com.behsazan.schemaforge.conformance.SchemaConformanceSummary;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaConformanceSelectedControllerTest {
    private static final String HEADER = RuntimeMetadataConnectionRegistry.HEADER;

    @Test void planRequiresSelectedConnectionAndDoesNotReadGlobalMetadata() throws Exception {
        var registry = new RuntimeMetadataConnectionRegistry();
        var service = mock(SchemaConformanceAuditService.class);
        var selection = registry.register(DatabasePlatform.POSTGRESQL,
                "jdbc:postgresql://example.invalid:5432/bank?ssl=true&another=value",
                "readonly", "SECRET_DO_NOT_LEAK", "PUBLIC", "public", List.of("public"), false,
                "bank", "PostgreSQL", "17");
        var mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service, registry)).build();

        mvc.perform(get("/api/v1/conformance/selected/plan"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/conformance/selected/plan").header(HEADER, "invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/conformance/selected/plan").header(HEADER, selection.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.platform").value("postgresql"))
                .andExpect(jsonPath("$.target.jdbcTarget").value("jdbc:postgresql://example.invalid:5432/bank"))
                .andExpect(jsonPath("$.target.databaseName").value("bank"))
                .andExpect(jsonPath("$.target.username").value("readonly"))
                .andExpect(jsonPath("$.target.verifiedSchema").value("PUBLIC"))
                .andExpect(jsonPath("$.target.databaseProduct").value("PostgreSQL"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.ruleFamilies.length()").value(8))
                .andExpect(jsonPath("$.notIncluded.length()").value(3))
                .andExpect(jsonPath("$.target.token").doesNotExist());
        verifyNoInteractions(service);
        assertEquals(SchemaConformanceAuditService.TABLE_RULE_FAMILIES,
                SchemaConformanceRuleCatalog.RULES.stream()
                        .map(SchemaConformanceRuleCatalog.Rule::id).toList());
    }

    @Test void selectedTableAndSchemaOnlyUseThatSessionsRepository() throws Exception {
        var registry = new RuntimeMetadataConnectionRegistry();
        var selection = registry.register(DatabasePlatform.ORACLE,
                "jdbc:oracle:thin:@//example.invalid:1521/TEST", "auditor", "private", "BANK",
                "BANK", List.of("BANK"), false, "TEST", "Oracle", "19c");
        var sessionRepository = registry.resolve(selection.token(), DatabasePlatform.ORACLE);
        var service = mock(SchemaConformanceAuditService.class);
        when(service.auditSelectedTable(DatabasePlatform.ORACLE, "BANK", "ACCOUNTS", sessionRepository))
                .thenReturn(report(SchemaConformanceScope.TABLE));
        when(service.auditSelectedSchema(DatabasePlatform.ORACLE, "BANK", sessionRepository))
                .thenReturn(report(SchemaConformanceScope.SCHEMA));
        var mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service, registry)).build();
        mvc.perform(get("/api/v1/conformance/selected/table")
                        .header(HEADER, selection.token()).param("schema", "BANK").param("table", "ACCOUNTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.platform").value("oracle"))
                .andExpect(jsonPath("$.target.jdbcTarget").value("jdbc:oracle:thin:@//example.invalid:1521/TEST"))
                .andExpect(jsonPath("$.target.username").value("auditor"))
                .andExpect(jsonPath("$.report.scope").value("TABLE"))
                .andExpect(jsonPath("$.report.summary.tablesScanned").value(1))
                .andExpect(jsonPath("$.auditedAt").isNotEmpty());
        mvc.perform(get("/api/v1/conformance/selected/schema")
                        .header(HEADER, selection.token()).param("schema", "BANK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.scope").value("SCHEMA"));
        verify(service).auditSelectedTable(DatabasePlatform.ORACLE, "BANK", "ACCOUNTS", sessionRepository);
        verify(service).auditSelectedSchema(DatabasePlatform.ORACLE, "BANK", sessionRepository);
        verifyNoMoreInteractions(service);

        registry.disconnect(selection.token());
        mvc.perform(get("/api/v1/conformance/selected/table")
                        .header(HEADER, selection.token()).param("schema", "BANK").param("table", "ACCOUNTS"))
                .andExpect(status().isUnauthorized());
    }

    @Test void selectedAuditNeverFallsBackToConfiguredJdbcOrAllowsUnselectedDbms() throws Exception {
        var registry = new RuntimeMetadataConnectionRegistry();
        var selected = registry.register(DatabasePlatform.MYSQL,
                "jdbc:mysql://example.invalid:3306/bank", "auditor", "pw", "bank");
        var service = mock(SchemaConformanceAuditService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SchemaConformanceController(service, registry)).build();
        mvc.perform(get("/api/v1/conformance/selected/table")
                .param("schema", "bank").param("table", "accounts"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/conformance/selected/schema")
                .header(HEADER, "unknown").param("schema", "bank"))
                .andExpect(status().isUnauthorized());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> registry.resolve(selected.token(), DatabasePlatform.POSTGRESQL));
        verifyNoInteractions(service);
    }

    private static SchemaConformanceReport report(SchemaConformanceScope scope) {
        return new SchemaConformanceReport(SchemaConformanceReport.CONTRACT,
                DatabasePlatform.ORACLE, scope, "BANK", scope == SchemaConformanceScope.TABLE ? "ACCOUNTS" : null,
                SchemaConformanceAuditService.TABLE_RULE_FAMILIES, List.of(),
                new SchemaConformanceSummary(1, 1, 0, 0, 0, 0, true), List.of());
    }
}
