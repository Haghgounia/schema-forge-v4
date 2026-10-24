package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.SchemaForgeApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-out must disable Glossary REST, reporting and H2 initialization. */
@SpringBootTest(
        classes = SchemaForgeApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "schemaforge.glossary.enabled=false",
                "schemaforge.metadata.oracle.enabled=false",
                "schemaforge.metadata.postgresql.enabled=false",
                "schemaforge.metadata.db2zos.enabled=false",
                "schemaforge.metadata.db2luw.enabled=false",
                "schemaforge.metadata.sqlserver.enabled=false",
                "schemaforge.metadata.mysql.enabled=false",
                "schemaforge.metadata.mariadb.enabled=false",
                "schemaforge.spell-check.enabled=false"
        })
class GlossaryExplicitlyDisabledStartupTest {
    @Autowired ApplicationContext context;

    @Test
    void explicitFalseDisablesGlossaryWithoutAffectingOtherServices() {
        assertTrue(context.getBeansOfType(GlossaryConfiguration.class).isEmpty());
        assertTrue(context.getBeansOfType(GlossaryController.class).isEmpty());
        assertTrue(context.getBeansOfType(GlossaryReviewController.class).isEmpty());
        assertTrue(context.getBeansOfType(GlossaryReportWriter.class).isEmpty());
    }
}
