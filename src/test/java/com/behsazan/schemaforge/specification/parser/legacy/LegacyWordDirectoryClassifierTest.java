package com.behsazan.schemaforge.specification.parser.legacy;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyWordDirectoryClassifierTest {
    @TempDir
    Path tempDir;

    @Test
    void classifiesPoiZipBombGuardAsSecuritySkipWithoutWeakeningTheGuard() {
        String message = "Zip bomb detected! The file would exceed the max. ratio of compressed file size "
                + "to the size of the expanded data. Limits: MIN_INFLATE_RATIO: 0.010000";

        assertTrue(LegacyWordDirectoryClassifier.isSecurityPolicySkip("java.io.IOException", message));
        assertFalse(LegacyWordDirectoryClassifier.isSecurityPolicySkip(
                "java.io.IOException", "other IO failure"));
        assertFalse(LegacyWordDirectoryClassifier.isSecurityPolicySkip(
                "java.lang.RuntimeException", message));
    }

    @Test
    void classifiesRealLegacyTableAndContentOnlyWordWithoutFilenameGuessing() throws Exception {
        Path tableSource = Path.of(getClass().getResource("/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        Path nested = Files.createDirectories(tempDir.resolve("nested/a/b"));
        Path tableCopy = nested.resolve(tableSource.getFileName());
        Files.copy(tableSource, tableCopy);

        Path notes = nested.resolve("meeting-notes.docx");
        try (XWPFDocument word = new XWPFDocument(); OutputStream output = Files.newOutputStream(notes)) {
            word.createParagraph().createRun().setText("Meeting notes. This document is not a table specification.");
            word.write(output);
        }

        var table = LegacyWordDirectoryClassifier.classify(tempDir, tableCopy);
        var nonTable = LegacyWordDirectoryClassifier.classify(tempDir, notes);

        assertEquals(LegacyWordDirectoryClassifier.Outcome.TABLE_SPEC, table.outcome());
        assertTrue(table.columnCount() > 0);
        assertEquals("nested/a/b/" + tableSource.getFileName(), table.relativePath().replace('\\', '/'));

        assertEquals(LegacyWordDirectoryClassifier.Outcome.NOT_TABLE_SPEC, nonTable.outcome());
        assertEquals("IGNORED", nonTable.parserStatus());
    }
}
