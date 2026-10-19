package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryH2AndReportTest {
    @TempDir Path temp;

    private GlossaryRepository createRepository() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + temp.resolve("term-db").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");
        source.setPassword("");
        return new GlossaryRepository(source);
    }

    @Test
    void crudApprovalSoftDeleteAndManifestTrackedUnknownReport() throws Exception {
        var repository = createRepository();
        var candidate = repository.create(new GlossaryTermRequest(
                "Customer", "مشتری", "CRM", GlossaryTermKind.ATOMIC, null));
        assertEquals(GlossaryTermStatus.CANDIDATE, candidate.status());
        assertFalse(repository.approvedLexicon().atomic().contains("CUSTOMER"));
        repository.update(candidate.id(), new GlossaryTermRequest(
                "Customer", "مشتری", "CRM", GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        assertTrue(repository.approvedLexicon().atomic().contains("CUSTOMER"));
        repository.create(new GlossaryTermRequest("Account", "حساب", null,
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        repository.create(new GlossaryTermRequest("Loan Origination Fee", "کارمزد", "Lending",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.APPROVED));

        var schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "CUSTOMER_ACCOUNT")
                        .addColumn(Column.required("LOAN_ORIGINATION_FEE", DataType.simple("NUMBER")))
                        .addColumn(Column.required("NEW_TERM", DataType.simple("VARCHAR2"))).build()).build();
        var models = List.of(new ArtifactManifestAssembler.ModelInput(
                "test.docx", schema, new ValidationReport(true, List.of())));
        var context = ArtifactGenerationContext.create(ArtifactOrigin.STANDARD_WORD, "test.docx");
        var mapper = new ObjectMapper();
        // The H2 datastore is an application dependency, not a generated artifact.
        // Keep the manifest output root separate from the test's persistent H2 file.
        Path artifacts = Files.createDirectories(temp.resolve("artifacts"));
        new GlossaryReportWriter(repository, mapper).write(artifacts, context, models);
        String csv = Files.readString(artifacts.resolve("reports/glossary/unknown-terms.csv"));
        assertTrue(csv.contains("NEW"));
        assertTrue(csv.contains("TERM"));
        assertFalse(csv.contains("ORIGINATION"));
        assertEquals(2, mapper.readTree(artifacts.resolve("reports/glossary/summary.json").toFile())
                .get("uniqueNewTerms").asInt());
        assertEquals("01_Atomic_Terms", mapper.readTree(artifacts.resolve("reports/glossary/summary.json").toFile())
                .get("referenceWorksheet").asText());
        assertEquals(2, context.ledger().snapshot().stream()
                .filter(a -> a.type() == ArtifactType.GLOSSARY_REPORT).count());
        new ArtifactManifestWriter(mapper).write(artifacts, context, "test", models, Map.of());
        assertTrue(Files.isRegularFile(artifacts.resolve("manifest.json")));
        assertFalse(Files.exists(artifacts.resolve("term-db.mv.db")),
                "Persistent H2 files must not be inside the artifact output root");

        var approval = repository.changeStatus(GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.APPROVED, GlossaryTermStatus.REJECTED);
        assertEquals(2, approval);
        assertFalse(repository.approvedLexicon().atomic().contains("CUSTOMER"));
        assertTrue(repository.delete(candidate.id()));
        assertTrue(repository.get(candidate.id()).isEmpty());
        assertFalse(repository.delete(candidate.id()));
        var recreated = repository.create(new GlossaryTermRequest(
                "Customer", "مشتری", "CRM", GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        assertEquals(candidate.id(), recreated.id());
    }

    @Test
    void importsOnlyAtomicSheetWithoutOverwritingManualTermsAndRequiresApproval() throws Exception {
        var repository = createRepository();
        repository.create(new GlossaryTermRequest("ACCOUNT", "Manual", "Governance",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var stream = new ByteArrayOutputStream()) {
            var atom = workbook.createSheet("01_Atomic_Terms");
            var header = atom.createRow(0);
            header.createCell(0).setCellValue("#");
            header.createCell(1).setCellValue("واژه فارسی");
            header.createCell(2).setCellValue("English Canonical");
            var row = atom.createRow(1);
            row.createCell(0).setCellValue(1); row.createCell(1).setCellValue("حساب"); row.createCell(2).setCellValue("ACCOUNT");
            row = atom.createRow(2);
            row.createCell(0).setCellValue(2); row.createCell(1).setCellValue("حساب"); row.createCell(2).setCellValue("ACCOUNT");
            row = atom.createRow(3);
            row.createCell(0).setCellValue(3); row.createCell(1).setCellValue("پرداخت پیش"); row.createCell(2).setCellValue("Advance Payment");
            var phrases = workbook.createSheet("02_Canonical_Phrases");
            header = phrases.createRow(0);
            header.createCell(0).setCellValue("واژه فارسی");
            header.createCell(1).setCellValue("English ");
            header.createCell(2).setCellValue("Module");
            row = phrases.createRow(1);
            row.createCell(0).setCellValue("کارمزد"); row.createCell(1).setCellValue("LOAN ORIGINATION FEE");
            row.createCell(2).setCellValue("Lending");
            workbook.write(stream);
            bytes = stream.toByteArray();
        }
        var file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var result = new GlossaryExcelImporter(repository).importWorkbook(file, GlossaryTermStatus.CANDIDATE);
        assertEquals(3, result.sourceRows());
        assertEquals(2, result.uniqueEntries());
        assertEquals(1, result.inserted());
        assertEquals("Manual", repository.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow().persian());
        assertTrue(repository.getByKey(GlossaryTermKind.PHRASE, "ADVANCE_PAYMENT").isPresent());
        assertEquals("ECOBANK_ATOMIC_XLSX", repository.getByKey(
                GlossaryTermKind.PHRASE, "ADVANCE_PAYMENT").orElseThrow().source());
        assertTrue(repository.getByKey(GlossaryTermKind.PHRASE, "LOAN_ORIGINATION_FEE").isEmpty());
        assertFalse(repository.approvedLexicon().phrases().contains("ADVANCE_PAYMENT"));
        var schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "LOAN_ORIGINATION_FEE")
                        .addColumn(Column.required("ACCOUNT", DataType.simple("NUMBER"))).build())
                .build();
        assertEquals(List.of("LOAN", "ORIGINATION", "FEE"),
                new GlossaryScanner().scan("legacy.docx", schema, repository.approvedLexicon())
                        .stream().map(UnknownGlossaryTerm::term).toList());
    }

    @Test
    void atomicImportWorksWithoutCanonicalPhrasesSheet() throws Exception {
        var repository = createRepository();
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var stream = new ByteArrayOutputStream()) {
            var atom = workbook.createSheet("01_Atomic_Terms");
            var header = atom.createRow(0);
            header.createCell(0).setCellValue("#");
            header.createCell(1).setCellValue("\u0648\u0627\u0698\u0647\u0020\u0641\u0627\u0631\u0633\u06cc");
            header.createCell(2).setCellValue("English Canonical");
            var row = atom.createRow(1);
            row.createCell(1).setCellValue("Account");
            row.createCell(2).setCellValue("ACCOUNT");
            workbook.write(stream);
            bytes = stream.toByteArray();
        }
        var file = new MockMultipartFile("file", "atomic-only.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var imported = new GlossaryExcelImporter(repository).importWorkbook(file, GlossaryTermStatus.CANDIDATE);
        assertEquals(1, imported.sourceRows());
        assertEquals(1, imported.uniqueEntries());
        assertEquals(1, imported.inserted());
        assertEquals(0, repository.approvedLexicon().atomic().size());
        assertEquals(1, repository.approvedLexicon().candidateAtomic().size());
        assertEquals(0, new GlossaryExcelImporter(repository)
                .importWorkbook(file, GlossaryTermStatus.CANDIDATE).inserted());
    }

    @Test
    void aWorkbookContainingOnlyCanonicalPhrasesIsRejected() throws Exception {
        var repository = createRepository();
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var stream = new ByteArrayOutputStream()) {
            workbook.createSheet("02_Canonical_Phrases");
            workbook.write(stream);
            bytes = stream.toByteArray();
        }
        var file = new MockMultipartFile("file", "phrases-only.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var error = assertThrows(IllegalArgumentException.class,
                () -> new GlossaryExcelImporter(repository).importWorkbook(file, GlossaryTermStatus.CANDIDATE));
        assertTrue(error.getMessage().contains("01_Atomic_Terms"));
        assertEquals(0, repository.search(null, null, null, 0, 100).total());
    }

    @Test
    void legacyCanonicalPhrasesAreOutOfScopeButAtomicWorksheetCanReactivateMatchingTerms() throws Exception {
        var repository = createRepository();
        repository.create(new GlossaryTermRequest("Advance Payment", "old", "Legacy",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        String url = "jdbc:h2:file:" + temp.resolve("term-db").toAbsolutePath().toString()
                .replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE glossary_term SET source='ECOBANK_XLSX' "
                    + "WHERE term_key='ADVANCE_PAYMENT'");
            statement.executeUpdate("INSERT INTO glossary_term "
                    + "(kind, term_key, english, status, source) VALUES "
                    + "('PHRASE', 'LOAN_ORIGINATION_FEE', 'Loan Origination Fee', "
                    + "'APPROVED', 'ECOBANK_XLSX')");
        }
        assertFalse(repository.approvedLexicon().candidatePhrases().contains("ADVANCE_PAYMENT"));
        assertFalse(repository.approvedLexicon().phrases().contains("LOAN_ORIGINATION_FEE"));
        assertEquals(0, repository.search(null, GlossaryTermKind.PHRASE, null, 0, 100).total());
        assertEquals(0, repository.changeStatus(null, GlossaryTermStatus.CANDIDATE,
                GlossaryTermStatus.APPROVED));

        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var stream = new ByteArrayOutputStream()) {
            var atom = workbook.createSheet("01_Atomic_Terms");
            var header = atom.createRow(0);
            header.createCell(0).setCellValue("#");
            header.createCell(1).setCellValue("\u0648\u0627\u0698\u0647\u0020\u0641\u0627\u0631\u0633\u06cc");
            header.createCell(2).setCellValue("English Canonical");
            var row = atom.createRow(1);
            row.createCell(1).setCellValue("Advance");
            row.createCell(2).setCellValue("Advance Payment");
            workbook.write(stream);
            bytes = stream.toByteArray();
        }
        var file = new MockMultipartFile("file", "atomic-legacy.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var result = new GlossaryExcelImporter(repository).importWorkbook(file, GlossaryTermStatus.APPROVED);
        assertEquals(1, result.sourceRows());
        assertEquals(0, result.inserted());
        assertEquals(1, repository.search(null, GlossaryTermKind.PHRASE, null, 0, 100).total());
        assertTrue(repository.approvedLexicon().candidatePhrases().contains("ADVANCE_PAYMENT"));
        assertFalse(repository.approvedLexicon().phrases().contains("ADVANCE_PAYMENT"));
        assertFalse(repository.approvedLexicon().phrases().contains("LOAN_ORIGINATION_FEE"));
        assertEquals(1, repository.changeStatus(GlossaryTermKind.PHRASE,
                GlossaryTermStatus.CANDIDATE, GlossaryTermStatus.APPROVED));
        assertTrue(repository.approvedLexicon().phrases().contains("ADVANCE_PAYMENT"));
    }

}
