package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9DependencySectionProbeTest {

    @Test
    void preservesCompleteDependencyPairWithoutInterpretingForeignSyntax() {
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "PRPSLCD", "TASSHMA.CTFPRPSL.PRPSLCD")
        ));

        var rows = LegacyRtl9DependencySectionProbe.extractRows(tables);

        assertEquals(1, rows.size());
        assertEquals("PRPSLCD", rows.getFirst().localFieldRaw());
        assertEquals("TASSHMA.CTFPRPSL.PRPSLCD", rows.getFirst().foreignFieldRaw());
        assertEquals("COMPLETE_PAIR", rows.getFirst().shape());
    }

    @Test
    void ignoresBlankTemplateRows() {
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "", "")
        ));

        assertEquals(1, LegacyRtl9DependencySectionProbe.countSections(tables));
        assertTrue(LegacyRtl9DependencySectionProbe.extractRows(tables).isEmpty());
    }

    @Test
    void preservesPartialRowsForFailClosedCorpusReview() {
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فيلد", "فيلد خارجي"),
                List.of("1", "CLTRLCD", "")
        ));

        var rows = LegacyRtl9DependencySectionProbe.extractRows(tables);

        assertEquals(1, rows.size());
        assertEquals("LOCAL_ONLY", rows.getFirst().shape());
    }
}
