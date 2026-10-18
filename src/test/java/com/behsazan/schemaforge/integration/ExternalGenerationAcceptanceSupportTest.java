package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalGenerationAcceptanceSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void observationModeNeverFailsOnlyBecauseFailuresWereRecorded() {
        List<String> failures = List.of("known corpus blocker");

        assertFalse(ExternalGenerationAcceptanceSupport.shouldFailGate(false, failures));
        assertTrue(ExternalGenerationAcceptanceSupport.shouldFailGate(true, failures));
        assertFalse(ExternalGenerationAcceptanceSupport.shouldFailGate(true, List.of()));
    }

    @Test
    void eaOutputNamesAreDeterministicAndCollisionSafeAcrossSubdirectories() throws Exception {
        Path inputRoot = Files.createDirectories(tempDir.resolve("ea"));
        Path first = Files.createDirectories(inputRoot.resolve("a")).resolve("Final.xml");
        Path second = Files.createDirectories(inputRoot.resolve("b")).resolve("Final.xml");
        Files.writeString(first, "<x/>");
        Files.writeString(second, "<x/>");
        Path outputRoot = Files.createDirectories(tempDir.resolve("out"));

        Path firstOutput = ExternalGenerationAcceptanceSupport.stableEaOutputPath(outputRoot, inputRoot, first);
        Path firstAgain = ExternalGenerationAcceptanceSupport.stableEaOutputPath(outputRoot, inputRoot, first);
        Path secondOutput = ExternalGenerationAcceptanceSupport.stableEaOutputPath(outputRoot, inputRoot, second);

        assertEquals(firstOutput, firstAgain);
        assertNotEquals(firstOutput, secondOutput);
        assertTrue(firstOutput.getFileName().toString().startsWith("Final-"));
        assertTrue(secondOutput.getFileName().toString().startsWith("Final-"));
    }

    @Test
    void noPhysicalTableEaInputIsClassifiedAsIntentionalSkip() throws Exception {
        Path input = tempDir.resolve("logical-model.xml").toAbsolutePath();
        IllegalArgumentException parserFailure = new IllegalArgumentException(
                "No Enterprise Architect table elements were found in XML/XMI: logical-model.xml");

        assertTrue(ExternalGenerationAcceptanceSupport.isNoPhysicalEaTableFailure(parserFailure));
        assertTrue(ExternalGenerationAcceptanceSupport.isNoPhysicalEaTableFailure(
                new RuntimeException("wrapped", parserFailure)));
        assertFalse(ExternalGenerationAcceptanceSupport.isNoPhysicalEaTableFailure(
                new IllegalArgumentException("different parser failure")));

        var row = ExternalGenerationAcceptanceSupport.skippedNoPhysicalTablesRow(
                "EA logical-model.xml", input, null);
        assertEquals("SKIPPED_NO_PHYSICAL_TABLES", row.status());
        assertEquals(1, row.skipped());
        assertEquals(0, row.failed());

        Path csv = ExternalGenerationAcceptanceSupport.writeSummaryCsv(
                tempDir, "skip-summary.csv", List.of(row));
        String text = Files.readString(csv);
        assertTrue(text.contains("SKIPPED_NO_PHYSICAL_TABLES"));
        assertTrue(text.contains("outside the physical-schema import contract"));
    }

    @Test
    void legacyDirectoryTableSpecZipPreservesRelativePathsAndSummaryCounts() throws Exception {
        Path inputRoot = Files.createDirectories(tempDir.resolve("legacy"));
        Path nested = Files.createDirectories(inputRoot.resolve("a/b"));
        Path first = nested.resolve("one.docx");
        Path second = inputRoot.resolve("two.doc");
        Files.writeString(first, "one");
        Files.writeString(second, "two");
        Path zip = ExternalGenerationAcceptanceSupport.createLegacyDirectoryTableSpecZip(
                inputRoot, tempDir.resolve("filtered.zip"), List.of(first, second));

        var entries = ExternalGenerationAcceptanceSupport.unzip(Files.readAllBytes(zip));
        assertTrue(entries.containsKey("a/b/one.docx"));
        assertTrue(entries.containsKey("two.doc"));

        var docRows = List.of(
                new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                        "a/b/one.docx", "one.docx", "TABLE_SPEC", "SUCCESS", 3, "", "", ""),
                new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                        "notes.docx", "notes.docx", "SKIPPED_NOT_TABLE_SPEC", "IGNORED", 0, "", "", ""));
        Path summary = ExternalGenerationAcceptanceSupport.writeLegacyDirectoryDocumentSummary(
                tempDir, "legacy-docs.csv", docRows);
        String text = Files.readString(summary);
        assertTrue(text.contains("SKIPPED_NOT_TABLE_SPEC"));
        assertTrue(text.contains("a/b/one.docx"));
    }

    @Test
    void summaryCsvCarriesExactInputOutputAndExceptionFields() throws Exception {
        Path input = tempDir.resolve("broken.xml").toAbsolutePath();
        Path output = tempDir.resolve("broken-result.zip").toAbsolutePath();
        IllegalStateException failure = new IllegalStateException("renderer failed");
        var row = ExternalGenerationAcceptanceSupport.failureSummaryRow("EA broken.xml", input, output, failure);

        Path csv = ExternalGenerationAcceptanceSupport.writeSummaryCsv(
                tempDir, "summary.csv", List.of(row));
        String text = Files.readString(csv);

        assertTrue(text.contains("inputName,output,status"));
        assertTrue(text.contains("broken.xml"));
        assertTrue(text.contains(output.toString()));
        assertTrue(text.contains("java.lang.IllegalStateException"));
        assertTrue(text.contains("renderer failed"));
    }
}
