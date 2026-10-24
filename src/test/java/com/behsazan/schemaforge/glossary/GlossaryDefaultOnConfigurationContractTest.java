package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guard the default across embedded and external distribution configurations. */
class GlossaryDefaultOnConfigurationContractTest {
    @Test
    void allShippedRuntimeConfigurationsEnableGlossaryAndKeepDryRunByDefault() throws IOException {
        for (String file : List.of("src/main/resources/application.yml",
                "distribution/config/application.yml", "distribution/config/application-example.yml")) {
            String yaml = Files.readString(Path.of(file));
            assertTrue(yaml.contains("enabled: ${SCHEMAFORGE_GLOSSARY_ENABLED:true}"), file);
            assertTrue(yaml.contains("mode: ${SCHEMAFORGE_GLOSSARY_CANDIDATE_REGISTRATION_MODE:DRY_RUN}"), file);
            assertTrue(yaml.contains("database-path: ${SCHEMAFORGE_GLOSSARY_DATABASE_PATH:./data/schemaforge-glossary}"), file);
        }
    }

    @Test
    void absentSettingActivatesAllGlossaryComponentsAndExplicitFalseStillOptsOut() {
        for (Class<?> type : List.of(GlossaryConfiguration.class,
                GlossaryController.class, GlossaryReviewController.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertTrue(condition.matchIfMissing(), type.getSimpleName());
            assertEquals("true", condition.havingValue(), type.getSimpleName());
        }
    }
}
