package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryAutomaticCandidateRegistrationTest {
    @TempDir Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    private DriverManagerDataSource source() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + temp.resolve("glossary-test").toAbsolutePath().toString()
                .replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    private void add(GlossaryRepository repository, String word, GlossaryTermStatus status) {
        repository.create(new GlossaryTermRequest(word, "", "", GlossaryTermKind.ATOMIC, status));
    }

    private DatabaseSchema schema(String... tableNames) {
        var builder = DatabaseSchema.builder("FIN");
        for (String table : tableNames) {
            builder.addTable(Table.builder("FIN", table)
                    .addColumn(Column.required("SCHOOL_ID", DataType.simple("INTEGER")))
                    .build());
        }
        return builder.build();
    }

    private JsonNode execute(GlossaryRepository repository, GlossaryReviewService reviews,
                             GlossaryCandidateRegistrationMode mode, DatabaseSchema schema,
                             String source, String outputName) throws Exception {
        var registrar = new GlossaryCandidateRegistrationService(mode, reviews, SpellCheckProperties.defaults());
        var writer = new GlossaryReportWriter(repository, mapper, registrar);
        var context = ArtifactGenerationContext.create(ArtifactOrigin.ENTERPRISE_ARCHITECT, source);
        var models = List.of(new ArtifactManifestAssembler.ModelInput(
                source, schema, new ValidationReport(true, List.of())));
        Path target = Files.createDirectories(temp.resolve(outputName));
        writer.write(target, context, models);
        assertEquals(2, context.ledger().snapshot().stream()
                .filter(a -> a.type() == ArtifactType.GLOSSARY_REPORT).count());
        assertTrue(Files.isRegularFile(target.resolve("reports/glossary/unknown-terms.csv")));
        return mapper.readTree(target.resolve("reports/glossary/summary.json").toFile());
    }

    @Test
    void dryRunUsesRealOfflineDictionaryButNeverWritesH2() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        add(repository, "ACCOUNT", GlossaryTermStatus.APPROVED);
        add(repository, "ID", GlossaryTermStatus.APPROVED);
        var json = execute(repository, reviews, GlossaryCandidateRegistrationMode.DRY_RUN,
                schema("SCHOOL_ACCOUNT", "CUTOMER_ZXQWV"), "deposit.xml", "dry-run");
        JsonNode result = json.path("candidateRegistration");
        assertEquals("DRY_RUN", result.path("mode").asText());
        assertTrue(result.path("eligibleTerms").toString().contains("SCHOOL"));
        assertTrue(result.path("suspectedTypos").toString().contains("CUTOMER"));
        assertTrue(result.path("manualReviewTerms").toString().contains("ZXQWV"));
        assertEquals(0, result.path("insertedCandidates").asInt());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").isEmpty());
        assertEquals(0, reviews.history(null, 0, 100).total());
    }

    @Test
    void automaticModeInsertsOnlyDictionaryConfirmedWordsOnceWithAuditableEvidence() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        add(repository, "ACCOUNT", GlossaryTermStatus.APPROVED);
        add(repository, "ID", GlossaryTermStatus.APPROVED);
        var json = execute(repository, reviews, GlossaryCandidateRegistrationMode.AUTO_CANDIDATE,
                schema("SCHOOL_ACCOUNT", "ACCOUNT_SCHOOL", "CUTOMER_ZXQWV"),
                "deposit.xml", "automatic");
        JsonNode result = json.path("candidateRegistration");
        assertEquals("AUTO_CANDIDATE", result.path("mode").asText());
        assertEquals(1, result.path("insertedCandidates").asInt());
        assertTrue(result.path("evidenceOccurrencesRecorded").asInt() >= 2);
        GlossaryTerm created = repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").orElseThrow();
        assertEquals(GlossaryTermStatus.CANDIDATE, created.status());
        assertEquals("AUTO_DISCOVERY", created.source());
        assertEquals("", created.persian());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "CUTOMER").isEmpty());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "ZXQWV").isEmpty());
        assertEquals(1, reviews.history("SCHOOL", 0, 100).total());
        assertEquals("AUTO_CANDIDATE", reviews.history("SCHOOL", 0, 100).items().getFirst().eventType());
        assertTrue(reviews.queue(false, 0, 100).items().stream()
                .anyMatch(item -> item.key().equals("SCHOOL") && item.occurrences() >= 2));
        var again = execute(repository, reviews, GlossaryCandidateRegistrationMode.AUTO_CANDIDATE,
                schema("SCHOOL_ACCOUNT", "ACCOUNT_SCHOOL", "CUTOMER_ZXQWV"),
                "deposit.xml", "automatic-again");
        assertEquals(0, again.path("candidateRegistration").path("insertedCandidates").asInt());
        assertEquals(created.id(), repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").orElseThrow().id());
        assertEquals(1, reviews.history("SCHOOL", 0, 100).total());
    }

    @Test
    void respectsExistingCandidateRejectedDeletedAndKnownSingularWithoutResurrection() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        add(repository, "PROFILE", GlossaryTermStatus.CANDIDATE);
        add(repository, "ACCOUNT", GlossaryTermStatus.APPROVED);
        add(repository, "CUSTOMER", GlossaryTermStatus.REJECTED);
        add(repository, "SCHOOL", GlossaryTermStatus.CANDIDATE);
        var school = repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").orElseThrow();
        assertTrue(repository.delete(school.id()));
        var json = execute(repository, reviews, GlossaryCandidateRegistrationMode.AUTO_CANDIDATE,
                schema("PROFILES_ACCOUNT", "CUSTOMER_SCHOOL"), "test.docx", "protected");
        assertEquals(0, json.path("candidateRegistration").path("insertedCandidates").asInt());
        assertEquals("REJECTED", repository.getByKey(GlossaryTermKind.ATOMIC, "CUSTOMER")
                .orElseThrow().status().name());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").isEmpty());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "PROFILES").isEmpty());
        assertTrue(json.path("pluralColumnViolations").asInt() >= 0);
    }

    @Test
    void preventsStaleSnapshotOverwritingSoftDeletedTermAndImportIsIdempotent() {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        add(repository, "SCHOOL", GlossaryTermStatus.APPROVED);
        var old = repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").orElseThrow();
        assertTrue(repository.delete(old.id()));
        UnknownGlossaryTerm finding = new UnknownGlossaryTerm(
                "old.xml", "TABLE", "SCHOOL", "", "SCHOOL", "SCHOOL", 1, "NEW");
        var stale = new GlossaryLexicon(Set.of(), Set.of());
        var registrar = new GlossaryCandidateRegistrationService(
                GlossaryCandidateRegistrationMode.AUTO_CANDIDATE, reviews, SpellCheckProperties.defaults());
        var outcome = registrar.process(List.of(finding), stale, "a".repeat(64), "run-123");
        assertEquals(0, outcome.insertedCandidates());
        assertEquals(1, outcome.alreadyKnown());
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").isEmpty());
        assertEquals(0, reviews.history("SCHOOL", 0, 100).total());
        var repeated = registrar.process(List.of(finding), stale, "a".repeat(64), "run-123");
        assertEquals(0, repeated.insertedCandidates());
    }

    @Test
    void unknownPluralIsHeldForSingularReviewInsteadOfCreatingPluralCandidate() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        var json = execute(repository, reviews, GlossaryCandidateRegistrationMode.AUTO_CANDIDATE,
                schema("SCHOOLS"), "schools.xml", "plural-new");
        JsonNode result = json.path("candidateRegistration");
        assertTrue(result.path("manualReviewTerms").toString().contains("SCHOOLS"));
        assertFalse(result.path("eligibleTerms").toString().contains("SCHOOLS"));
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOLS").isEmpty());
    }

    @Test
    void twoConcurrentRegistrationsOfSameEvidenceCreateOnlyOneTermAndEvent() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        var registration = new GlossaryCandidateRegistrationService(
                GlossaryCandidateRegistrationMode.AUTO_CANDIDATE, reviews, SpellCheckProperties.defaults());
        var findings = List.of(new UnknownGlossaryTerm(
                "deposit.xml", "TABLE", "SCHOOL", "", "SCHOOL", "SCHOOL", 1, "NEW"));
        var lexicon = repository.approvedLexicon();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<GlossaryCandidateRegistrationService.Result> action = () -> {
                gate.await();
                return registration.process(findings, lexicon, "b".repeat(64), "generation-one");
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            gate.countDown();
            assertEquals(1, first.get().insertedCandidates() + second.get().insertedCandidates());
        }
        assertEquals(1, reviews.history("SCHOOL", 0, 100).total());
        assertEquals(1, repository.search("SCHOOL", GlossaryTermKind.ATOMIC, null, 0, 100).total());
    }

    @Test
    void failedEvidenceBatchRollsBackCandidateAndAuditTogether() {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        var registration = new GlossaryCandidateRegistrationService(
                GlossaryCandidateRegistrationMode.AUTO_CANDIDATE, reviews, SpellCheckProperties.defaults());
        var findings = List.of(new UnknownGlossaryTerm(
                "file".repeat(400), "TABLE", "SCHOOL", "", "SCHOOL", "SCHOOL", 1, "NEW"));
        assertThrows(RuntimeException.class, () -> registration.process(
                findings, repository.approvedLexicon(), "c".repeat(64), "generation-two"));
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").isEmpty());
        assertEquals(0, reviews.history("SCHOOL", 0, 100).total());
    }

    @Test
    void offModeAndInvalidConfigurationStaySafe() throws Exception {
        var dataSource = source();
        var repository = new GlossaryRepository(dataSource);
        var reviews = new GlossaryReviewService(dataSource);
        assertThrows(IllegalArgumentException.class, () -> GlossaryCandidateRegistrationMode.parse("ONLINE"));
        assertEquals(GlossaryCandidateRegistrationMode.AUTO_CANDIDATE,
                GlossaryCandidateRegistrationMode.parse("auto_candidate"));
        var result = execute(repository, reviews, GlossaryCandidateRegistrationMode.OFF,
                schema("SCHOOL"), "source.docx", "off");
        assertFalse(result.has("candidateRegistration"));
        assertTrue(repository.getByKey(GlossaryTermKind.ATOMIC, "SCHOOL").isEmpty());
    }
}
