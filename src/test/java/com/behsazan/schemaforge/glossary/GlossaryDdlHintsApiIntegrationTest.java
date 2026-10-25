package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.NumericMappingProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Demonstrates that REST Standard Word generation uses current H2 Glossary in actual DDL files. */
class GlossaryDdlHintsApiIntegrationTest {
    @TempDir Path temp;

    @Test
    void hintsAreWrittenOnTheColumnRowOnlyWhenGlossaryIsEnabled() throws Exception {
        var ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:file:" + temp.resolve("terms").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        var repository = new GlossaryRepository(ds);
        repository.create(new GlossaryTermRequest("COUNTRY", null, null,
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.CANDIDATE));
        repository.create(new GlossaryTermRequest("ID", null, null,
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        ObjectMapper mapper = new ObjectMapper();
        var resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());
        var spell = SpellCheckProperties.defaults();
        spell.setEnabled(false);
        Path doc = Path.of("src/test/resources/physical-real-corpus/MCB.BIM.TBL.COUNTRIES.V1.1.docx");
        var input = new MockMultipartFile("file", doc.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(doc));
        var service = new SchemaForgeApiService(AuditProperties.defaults(), GrantProperties.defaults(),
                spell, mapper, resolver, EaImportProperties.defaults(), NumericMappingProperties.defaults(),
                NamingValidationProperties.defaults(), Optional.of(new GlossaryReportWriter(repository, mapper)));
        byte[] archive = service.generateFromWord(input, null, "AUTO", List.of("oracle"));
        Map<String, byte[]> files = unzip(archive);
        String ddl = new String(files.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("ddl/oracle/"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(ddl.contains("/* GH: "), "Unlisted Word tokens should be hinted");
        assertTrue(ddl.lines().anyMatch(line -> line.contains("/* GH: ")
                && line.matches("(?i).*\\s+[A-Z0-9_]+\\s+[^\\r\\n]*,?\\s*/\\* GH: [A-Z0-9_,]+ \\*/.*")),
                "Hints belong on the real SQL column definition line");
        String report = new String(files.get("reports/glossary/unknown-terms.csv"), StandardCharsets.UTF_8);
        assertTrue(report.contains("PENDING_APPROVAL"));
        assertTrue(report.contains("NEW"));
        assertNotNull(files.get("manifest.json"));
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }
}
