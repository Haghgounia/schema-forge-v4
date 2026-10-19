package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.error.SchemaForgeRestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlossaryRestControllerTest {
    @TempDir Path temp;

    @Test
    void CRUDIsExposedAsVersionedRestAndMutationsRequireAdminKey() throws Exception {
        var db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:file:" + temp.resolve("api-glossary").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
        db.setUsername("sa");
        db.setPassword("");
        var repo = new GlossaryRepository(db);
        var mvc = MockMvcBuilders.standaloneSetup(new GlossaryController(
                repo, new GlossaryExcelImporter(repo), new GlossaryWriteGuard("test-secret")))
                .setControllerAdvice(new SchemaForgeRestExceptionHandler()).build();

        mvc.perform(get("/api/v1/glossary/terms"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        String term = "{\"english\":\"CUSTOMER\",\"persian\":\"مشتری\",\"kind\":\"ATOMIC\",\"status\":\"APPROVED\"}";
        mvc.perform(post("/api/v1/glossary/terms").contentType(MediaType.APPLICATION_JSON).content(term))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.contract").value("schemaforge-rest-error/v1"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/glossary/terms").header("X-Glossary-Admin-Key", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(term))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.key").value("CUSTOMER"));
        mvc.perform(post("/api/v1/glossary/terms").header("X-Glossary-Admin-Key", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(term))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.contract").value("schemaforge-rest-error/v1"))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/v1/glossary/terms").param("q", "CUS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/glossary/export.csv"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/glossary/terms/1").header("X-Glossary-Admin-Key", "test-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/glossary/terms/1")).andExpect(status().isNotFound());
    }
}
