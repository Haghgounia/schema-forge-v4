package com.behsazan.schemaforge.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;

/** Opt-in isolated persistent glossary datastore; does not alter DBMS metadata DataSources. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "schemaforge.glossary", name = "enabled", havingValue = "true")
public class GlossaryConfiguration {
    @Bean("glossaryDataSource")
    DataSource glossaryDataSource(@Value("${schemaforge.glossary.database-path:./data/schemaforge-glossary}") String path)
            throws IOException {
        Path databasePath = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(databasePath.getParent());
        DriverManagerDataSource datasource = new DriverManagerDataSource();
        datasource.setDriverClassName("org.h2.Driver");
        datasource.setUrl("jdbc:h2:file:" + databasePath.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        datasource.setUsername("sa");
        datasource.setPassword("");
        return datasource;
    }

    @Bean
    GlossaryRepository glossaryRepository(@Qualifier("glossaryDataSource") DataSource datasource) {
        return new GlossaryRepository(datasource);
    }

    @Bean
    GlossaryReviewService glossaryReviewService(@Qualifier("glossaryDataSource") DataSource datasource) {
        return new GlossaryReviewService(datasource);
    }

    @Bean
    GlossaryExcelImporter glossaryExcelImporter(GlossaryRepository repository) {
        return new GlossaryExcelImporter(repository);
    }

    /**
     * Preloads the authoritative Atomic Terms worksheet on application startup.
     * Re-running import is idempotent and never overwrites curated term/status edits.
     * The glossary must remain explicitly enabled; all other SchemaForge paths are unchanged.
     */
    @Bean
    ApplicationRunner glossaryAtomicBootstrap(GlossaryExcelImporter importer,
            @Value("${schemaforge.glossary.seed-on-startup:true}") boolean seedOnStartup) {
        return args -> {
            if (!seedOnStartup) return;
            var resource = new ClassPathResource("glossary/01_Atomic_Terms.xlsx");
            try (InputStream stream = resource.getInputStream()) {
                var imported = importer.importWorkbook(stream, GlossaryTermStatus.CANDIDATE);
                if (imported.sourceRows() != 946 || imported.uniqueEntries() != 804) {
                    throw new IllegalStateException("Unexpected Atomic glossary baseline: "
                            + imported.sourceRows() + " rows, "
                            + imported.uniqueEntries() + " unique terms");
                }
            }
        };
    }

    @Bean
    GlossaryWriteGuard glossaryWriteGuard(
            @Value("${schemaforge.glossary.admin-key:}") String adminKey) {
        return new GlossaryWriteGuard(adminKey);
    }

    @Bean
    GlossaryReportWriter glossaryReportWriter(GlossaryRepository repository, ObjectMapper mapper) {
        return new GlossaryReportWriter(repository, mapper);
    }
}
