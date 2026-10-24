package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.SchemaForgeApiApplication;
import com.behsazan.schemaforge.glossary.GlossaryController;
import com.behsazan.schemaforge.glossary.GlossaryReportWriter;
import com.behsazan.schemaforge.glossary.GlossaryReviewController;
import com.behsazan.schemaforge.glossary.GlossaryCandidateRegistrationMode;
import com.behsazan.schemaforge.glossary.GlossaryCandidateRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Freezes the default runtime contract: optional IBM JCC must not be required
 * unless Db2 LUW metadata access is explicitly enabled.
 */
@SpringBootTest(
        classes = SchemaForgeApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "schemaforge.metadata.oracle.enabled=false",
                "schemaforge.metadata.postgresql.enabled=false",
                "schemaforge.metadata.db2zos.enabled=false",
                "schemaforge.metadata.sqlserver.enabled=false",
                "schemaforge.metadata.mysql.enabled=false",
                "schemaforge.metadata.mariadb.enabled=false",
                "schemaforge.spell-check.enabled=false",
                // Keep the smoke context isolated from data/schemaforge-glossary.mv.db.
                "schemaforge.glossary.database-path=./target/glossary-default-on-startup-smoke",
                "schemaforge.glossary.seed-on-startup=false"
        })
class DefaultRuntimeStartupContractTest {
    @Autowired ApplicationContext context;

    @Test
    void applicationContextStartsWithoutOptionalDb2LuwJccDriver() {
        // Context startup is the assertion. Before R11.4.1 this failed with
        // ClassNotFoundException: com.ibm.db2.jcc.DB2Driver.
        // Glossary is now on without any explicit 'enabled=true' property.
        assertNotNull(context.getBean(GlossaryController.class));
        assertNotNull(context.getBean(GlossaryReviewController.class));
        assertNotNull(context.getBean(GlossaryReportWriter.class));
        assertEquals(GlossaryCandidateRegistrationMode.DRY_RUN,
                context.getBean(GlossaryCandidateRegistrationService.class).mode());
    }
}
