package com.behsazan.schemaforge.api.error;

import com.behsazan.schemaforge.api.MermaidDiagramApiService;
import com.behsazan.schemaforge.api.MermaidDiagramController;
import com.behsazan.schemaforge.api.OracleCrudController;
import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.api.SchemaForgeController;
import com.behsazan.schemaforge.api.SqlServerCrudController;
import com.behsazan.schemaforge.application.OracleCrudGenerationService;
import com.behsazan.schemaforge.application.ServiceUnavailableException;
import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaForgeRestContractMvcTest {

    @Test
    void generationErrorsUseStandardContract() throws Exception {
        SchemaForgeApiService service = mock(SchemaForgeApiService.class);
        when(service.generateFromWord(any())).thenThrow(new IllegalArgumentException("invalid word"));
        MockMvc mvc = mvc(new SchemaForgeController(service));
        MockMultipartFile file = new MockMultipartFile(
                "file", "table.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "x".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/generate/word").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.contract").value(RestErrorResponse.CONTRACT))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid word"))
                .andExpect(jsonPath("$.path").value("/api/v1/generate/word"))
                .andExpect(header().exists(SchemaForgeRequestCorrelationFilter.HEADER_NAME))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingMultipartPartUsesStandardContract() throws Exception {
        SchemaForgeApiService service = mock(SchemaForgeApiService.class);
        MockMvc mvc = mvc(new SchemaForgeController(service));

        mvc.perform(multipart("/api/v1/generate/word"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PART"))
                .andExpect(jsonPath("$.details.part").value("file"));
    }

    @Test
    void unsupportedHttpMethodUses405Contract() throws Exception {
        SchemaForgeApiService service = mock(SchemaForgeApiService.class);
        MockMvc mvc = mvc(new SchemaForgeController(service));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/generate/word"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeUses415Contract() throws Exception {
        SchemaForgeApiService service = mock(SchemaForgeApiService.class);
        MockMvc mvc = mvc(new SchemaForgeController(service));

        mvc.perform(post("/api/v1/generate/word")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unacceptableResponseMediaTypeUses406Contract() throws Exception {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        MockMvc mvc = mvc(new OracleCrudController(service));

        mvc.perform(post("/api/v1/generate/oracle/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"schema\":\"BIM\",\"table\":\"PROVINCES\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void metadataUnavailableUses503Contract() throws Exception {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        when(service.generate("BIM", "PROVINCES")).thenThrow(new ServiceUnavailableException("metadata disabled"));
        MockMvc mvc = mvc(new OracleCrudController(service));

        mvc.perform(post("/api/v1/generate/oracle/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schema\":\"BIM\",\"table\":\"PROVINCES\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("metadata disabled"));
    }

    @Test
    void sqlAcceptHeaderDoesNotSuppressJsonErrorContract() throws Exception {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        when(service.generate("BIM", "PROVINCES")).thenThrow(new IllegalArgumentException("invalid table request"));
        MockMvc mvc = mvc(new OracleCrudController(service));

        mvc.perform(post("/api/v1/generate/oracle/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.parseMediaType("application/sql"))
                        .content("{\"schema\":\"BIM\",\"table\":\"PROVINCES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedJsonUses400Contract() throws Exception {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        MockMvc mvc = mvc(new OracleCrudController(service));

        mvc.perform(post("/api/v1/generate/oracle/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void mermaidControllerValidationUsesStandardContract() throws Exception {
        MermaidDiagramApiService service = mock(MermaidDiagramApiService.class);
        MockMvc mvc = mvc(new MermaidDiagramController(service));
        MockMultipartFile file = new MockMultipartFile(
                "file", "model.schema.json", MediaType.APPLICATION_JSON_VALUE, "{}".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/diagram/mermaid/canonical-json")
                        .file(file)
                        .param("scope", "table")
                        .param("root", "ACCOUNT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SCHEMA.TABLE")));
    }

    @Test
    void sqlServerControllerValidationUsesStandardContract() throws Exception {
        SqlServerCrudGenerationService service = mock(SqlServerCrudGenerationService.class);
        when(service.generate("", "PROVINCES")).thenThrow(new IllegalArgumentException("schema must not be blank"));
        MockMvc mvc = mvc(new SqlServerCrudController(service));

        mvc.perform(post("/api/v1/generate/sqlserver/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schema\":\"\",\"table\":\"PROVINCES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("schema must not be blank"));
    }

    @Test
    void successfulResponsesAlsoCarryRequestId() throws Exception {
        OracleCrudGenerationService service = mock(OracleCrudGenerationService.class);
        when(service.generate("BIM", "PROVINCES"))
                .thenReturn(new OracleCrudGenerationService.OracleCrudGenerationResult(
                        "BIM.PROVINCES_20260823_010000_000.oracle.crud-package.sql",
                        "CREATE OR REPLACE PACKAGE BIM.PKG_PROVINCES AS END;"));
        MockMvc mvc = mvc(new OracleCrudController(service));

        mvc.perform(post("/api/v1/generate/oracle/crud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schema\":\"BIM\",\"table\":\"PROVINCES\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(SchemaForgeRequestCorrelationFilter.HEADER_NAME))
                .andExpect(content().contentType("application/sql;charset=UTF-8"));
    }

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SchemaForgeRestExceptionHandler())
                .addFilters(new SchemaForgeRequestCorrelationFilter())
                .build();
    }
}
