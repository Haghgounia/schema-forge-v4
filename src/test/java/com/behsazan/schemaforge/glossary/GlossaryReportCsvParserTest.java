package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryReportCsvParserTest {
    private static final String HEADER = GlossaryCsv.row("source_name", "object_type", "table_name",
            "column_name", "identifier", "unknown_term", "token_position", "review_status");

    @Test
    void utf8BomEscapedQuotesAndCommasRetainEvidenceExactly() {
        String csv = "\uFEFF" + HEADER + GlossaryCsv.row("a,\"b\".docx", "TABLE",
                "ACCOUNT_NEWWORD", "", "ACCOUNT_NEWWORD", "NEWWORD", "2", "NEW");
        var parsed = GlossaryReportCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, parsed.sourceRows());
        assertEquals(1, parsed.newOccurrences().size());
        assertEquals("a,\"b\".docx", parsed.newOccurrences().getFirst().source());
        assertEquals("NEWWORD", parsed.newOccurrences().getFirst().term());
    }

    @Test
    void existingPendingAndRejectedTermsAreNotCreatedAsNewCandidates() {
        String csv = HEADER
                + GlossaryCsv.row("a.docx", "TABLE", "ACCOUNT", "", "ACCOUNT", "ACCOUNT", "1", "PENDING_APPROVAL")
                + GlossaryCsv.row("a.docx", "TABLE", "DECLINED", "", "DECLINED", "DECLINED", "1", "REJECTED");
        var parsed = GlossaryReportCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, parsed.skippedNonNew());
        assertEquals(0, parsed.newOccurrences().size());
    }

    @Test
    void incompleteInvalidAndOversizedUploadsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> GlossaryReportCsvParser.parse(new byte[] {(byte) 0xff}));
        assertThrows(IllegalArgumentException.class,
                () -> GlossaryReportCsvParser.parse(new byte[GlossaryReportCsvParser.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> GlossaryReportCsvParser.parse(
                (HEADER + "\"incomplete").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> GlossaryReportCsvParser.parse(
                (HEADER + GlossaryCsv.row("file.docx", "TABLE", "OTHER", "", "OTHER",
                        "FORGED", "1", "NEW")).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> GlossaryReportCsvParser.parse(
                (HEADER + GlossaryCsv.row("file.docx", "TABLE", "ADVANCE_PAYMENT", "",
                        "ADVANCE_PAYMENT", "ADVANCE_PAYMENT", "1", "NEW"))
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
