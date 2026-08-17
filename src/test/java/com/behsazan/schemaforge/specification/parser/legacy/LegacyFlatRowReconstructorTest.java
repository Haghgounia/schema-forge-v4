package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyFlatRowReconstructorTest {

    @Test
    void splitsFlattenedFieldAndTypeSequencesWhenStructuralCellsAreUnambiguous() {
        List<List<String>> table = List.of(
                List.of("عنوان", "DBBRANCH SUPPORTDBBRANCH", "int int", "", "", "", "", "", "", ""),
                List.of("عنوان", "SETTLEFLAG STMTCREDIT", "tin big", "", "", "", "", "", "", "")
        );
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(table);

        List<List<String>> rows = LegacyFlatRowReconstructor.split(table.getFirst(), layout);

        assertEquals(2, rows.size());
        assertEquals("DBBRANCH", rows.get(0).get(1));
        assertEquals("int", rows.get(0).get(2));
        assertEquals("SUPPORTDBBRANCH", rows.get(1).get(1));
        assertEquals("int", rows.get(1).get(2));
    }

    @Test
    void preservesPerFieldLengthsAndPhysicalTypesWhenTheyAreAligned() {
        List<String> row = List.of(
                "", "FIRST SECOND", "VC VC", "20 30", "", "", "", "VARCHAR VARCHAR", "20 30", "");
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(List.of(row));

        List<List<String>> rows = LegacyFlatRowReconstructor.split(row, layout);

        assertEquals(2, rows.size());
        assertEquals("20", rows.get(0).get(3));
        assertEquals("30", rows.get(1).get(3));
        assertEquals("VARCHAR", rows.get(0).get(7));
        assertEquals("VARCHAR", rows.get(1).get(7));
    }

    @Test
    void splitsFlattenedRowsWhenMandatoryCheckmarksRemainCardinalityAligned() {
        List<String> row = List.of(
                "", "FIRST SECOND THIRD", "big big big", "", "", "", "✓✓ ✓", "", "", "");
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(List.of(row));

        List<List<String>> rows = LegacyFlatRowReconstructor.split(row, layout);

        assertEquals(3, rows.size());
        assertEquals("Y", rows.get(0).get(6));
        assertEquals("Y", rows.get(1).get(6));
        assertEquals("Y", rows.get(2).get(6));
    }

    @Test
    void retriesStructuredFieldCellsAfterParagraphAwareSplitCouldNotAlignTheRow() {
        String sep = String.valueOf(TextNormalizer.CELL_PARAGRAPH_SEPARATOR);
        List<String> row = List.of(
                "", "Timex" + sep + "Timez", "TS TS", "", "", "", "√ √", "", "", "");
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(List.of(row));

        List<List<String>> rows = LegacyFlatRowReconstructor.split(row, layout);

        assertEquals(2, rows.size());
        assertEquals("Timex", rows.get(0).get(1));
        assertEquals("Timez", rows.get(1).get(1));
        assertEquals("TS", rows.get(0).get(2));
        assertEquals("TS", rows.get(1).get(2));
    }

    @Test
    void splitsSingleStructuredMandatoryParagraphWhenCheckmarkCardinalityIsExact() {
        String sep = String.valueOf(TextNormalizer.CELL_PARAGRAPH_SEPARATOR);
        List<String> row = List.of(
                "", "NormalMaxAmnt CodedMinAmnt CodedMaxAmnt", "big big big", "",
                "", "", sep + "✓✓ ✓" + sep, "", "", "");
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(List.of(row));

        List<List<String>> rows = LegacyFlatRowReconstructor.split(row, layout);

        assertEquals(3, rows.size());
        assertEquals("NormalMaxAmnt", rows.get(0).get(1));
        assertEquals("CodedMinAmnt", rows.get(1).get(1));
        assertEquals("CodedMaxAmnt", rows.get(2).get(1));
        assertEquals("Y", rows.get(0).get(6));
        assertEquals("Y", rows.get(1).get(6));
        assertEquals("Y", rows.get(2).get(6));
    }

    @Test
    void refusesWrappedSingleFieldNamesAndUnalignedSemanticEvidence() {
        List<String> wrappedName = List.of(
                "", "UnderAmountExpi reDate", "VARCHAR", "30", "", "", "", "", "", "");
        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(List.of(wrappedName));
        assertEquals(1, LegacyFlatRowReconstructor.split(wrappedName, layout).size());

        List<String> ambiguousKey = List.of(
                "", "FIRST SECOND", "int int", "", "PK", "", "", "", "", "");
        layout = ColumnLayoutResolver.resolve(List.of(ambiguousKey));
        assertEquals(1, LegacyFlatRowReconstructor.split(ambiguousKey, layout).size());
    }
}
