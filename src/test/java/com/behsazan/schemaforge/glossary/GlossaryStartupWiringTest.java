package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/** Integration gate for explicit H2 startup and optional injection into ZIP artifact generation. */
@SpringBootTest(properties = {
        "schemaforge.glossary.enabled=true",
        "schemaforge.glossary.database-path=./target/glossary-spring-wiring-db",
        "schemaforge.glossary.admin-key=integration-test-only"
})
class GlossaryStartupWiringTest {
    @Autowired ApplicationContext context;
    @Autowired GlossaryRepository repository;

    @Test
    void enabledGlossaryHasIsolatedStoreRestEndpointsAndReportWriter() {
        assertNotNull(repository);
        assertNotNull(context.getBean(GlossaryReportWriter.class));
        assertNotNull(context.getBean(GlossaryController.class));
        assertNotNull(context.getBean(GlossaryReviewService.class));
        assertNotNull(context.getBean(GlossaryReviewController.class));
        assertNotNull(context.getBean(SchemaForgeApiService.class));
        assertTrue(repository.search(null, null, null, 0, 10).total() >= 804);
        assertEquals("ECOBANK_ATOMIC_XLSX", repository.getByKey(
                GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow().source());
        // The integration DB persists across test executions; pre-existing curated terms survive.
        assertTrue(repository.approvedLexicon().atomic().size() >= 1);
    }
}
