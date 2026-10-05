package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9IndexSectionResolverTest {

    @Test
    void recoversPrimaryAndCompositeIndexesFromSeparateFacilityGrid() {
        List<ColumnDefinition> columns = List.of(
                column(1, "CNTRCTID"),
                column(2, "INTCSTMRCD"),
                column(3, "PRPSLTYPCD"),
                column(4, "CNTRCTORGUNITCD")
        );
        List<List<List<String>>> tables = List.of(
                List.of(
                        List.of("ردیف", "نام صفت خاصه", "نام فیلد", "نوع", "طول", "کلید", "اجباری", "پیش فرض", "توضیحات"),
                        List.of("", "شماره قرارداد", "CNTRCTID", "D", "18", "", "X", "", "")
                ),
                List.of(
                        List.of("ردیف", "نوع شاخص", "نام فیلد"),
                        List.of("", "کلیداصلی", "CNTRCTID"),
                        List.of("", "غیر یکتا", "PRPSLTYPCD"),
                        List.of("", "غیر یکتا(ترکیبی)", "PRPSLTYPCDوCNTRCTORGUNITCD"),
                        List.of("", "غیر یکتا", "INTCSTMRCD")
                )
        );
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9IndexSectionResolver.apply(columns, tables, warnings);

        assertTrue(warnings.isEmpty());
        assertEquals(List.of("PK:1"), find(resolved, "CNTRCTID").keys());
        assertEquals(List.of("IX1:1", "IX2:1"), find(resolved, "PRPSLTYPCD").indexes());
        assertEquals(List.of("IX2:2"), find(resolved, "CNTRCTORGUNITCD").indexes());
        assertEquals(List.of("IX3:1"), find(resolved, "INTCSTMRCD").indexes());
    }

    @Test
    void acceptsOptionalMachineColumnAndUniqueIndexWithoutCreatingUniqueKeyEvidence() {
        List<ColumnDefinition> columns = List.of(column(1, "DSSTRID"), column(2, "DSSTRDSC"));
        List<List<List<String>>> tables = List.of(
                List.of(
                        List.of("ماشین", "ردیف", "نوع شاخص", "نام فیلد"),
                        List.of("DEV", "", "کلید اصلی", "DSSTRID"),
                        List.of("DEV", "", "یکتا", "DSSTRDSC")
                )
        );
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9IndexSectionResolver.apply(columns, tables, warnings);

        assertTrue(warnings.isEmpty());
        assertEquals(List.of("PK:1"), find(resolved, "DSSTRID").keys());
        assertTrue(find(resolved, "DSSTRDSC").keys().isEmpty());
        assertEquals(List.of("UIX1:1"), find(resolved, "DSSTRDSC").indexes());
    }

    @Test
    void acceptsSafeGenericNormalIndexLabelsButKeepsMissingAndCompositeKeyTypesFailClosed() {
        List<ColumnDefinition> columns = List.of(
                column(1, "A"),
                column(2, "B"),
                column(3, "C"),
                column(4, "D"),
                column(5, "E")
        );
        List<List<List<String>>> tables = List.of(
                List.of(
                        List.of("ردیف", "نوع شاخص", "نام فیلد"),
                        List.of("", "INDEX", "A+B"),
                        List.of("", "ایندکس 1", "C"),
                        List.of("", "معمولی", "D"),
                        List.of("", "کلید ( ترکیبی)", "A+C"),
                        List.of("", "", "E")
                )
        );
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9IndexSectionResolver.apply(columns, tables, warnings);

        assertEquals(List.of("IX1:1"), find(resolved, "A").indexes());
        assertEquals(List.of("IX1:2"), find(resolved, "B").indexes());
        assertEquals(List.of("IX2:1"), find(resolved, "C").indexes());
        assertEquals(List.of("IX3:1"), find(resolved, "D").indexes());
        assertTrue(find(resolved, "E").indexes().isEmpty());
        assertTrue(warnings.stream().anyMatch(w ->
                "LEGACY_INDEX_SECTION_COMPOSITE_KEY_AMBIGUOUS".equals(w.code())));
        assertTrue(warnings.stream().anyMatch(w ->
                "LEGACY_INDEX_SECTION_TYPE_NOT_PRESENT".equals(w.code())));
        assertFalse(warnings.stream().anyMatch(w ->
                "LEGACY_INDEX_SECTION_TYPE_UNRECOGNIZED".equals(w.code())));
    }

    @Test
    void failsClosedWhenIndexRowReferencesUnknownFieldOrUnknownType() {
        List<ColumnDefinition> columns = List.of(column(1, "KNOWNCOL"));
        List<List<List<String>>> tables = List.of(
                List.of(
                        List.of("ردیف", "نوع شاخص", "نام فیلد"),
                        List.of("", "غیر یکتا", "KNOWNCOLوMISSINGCOL"),
                        List.of("", "شاخص خاص", "KNOWNCOL")
                )
        );
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9IndexSectionResolver.apply(columns, tables, warnings);

        assertTrue(find(resolved, "KNOWNCOL").indexes().isEmpty());
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> "LEGACY_INDEX_SECTION_FIELD_UNRESOLVED".equals(w.code())));
        assertTrue(warnings.stream().anyMatch(w -> "LEGACY_INDEX_SECTION_TYPE_UNRECOGNIZED".equals(w.code())));
    }

    private static ColumnDefinition find(List<ColumnDefinition> columns, String name) {
        return columns.stream().filter(column -> column.fieldName().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    private static ColumnDefinition column(int sequence, String name) {
        return new ColumnDefinition(
                sequence,
                0,
                sequence,
                "",
                name,
                name,
                "INTEGER",
                "",
                "",
                "",
                "",
                Boolean.TRUE,
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
