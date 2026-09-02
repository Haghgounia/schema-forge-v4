package com.behsazan.schemaforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaForgeArchiveResponseNamingTest {

    @Test
    void wordResponseUsesCentralPortableArchiveName() throws Exception {
        SchemaForgeApiService service = mock(SchemaForgeApiService.class);
        when(service.generateFromWord(any(), any(), any())).thenReturn(new byte[] {1, 2, 3});
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchemaForgeController(service)).build();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "table.docx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "x".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/generate/word").file(file))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
                    assertNotNull(disposition);
                    assertTrue(
                            disposition.matches("attachment; filename=\\\"?schemaforge-word-\\d{8}-\\d{6}-\\d{3}\\.zip\\\"?"),
                            disposition);
                    assertTrue(!disposition.contains("_"), disposition);
                });
    }
}
