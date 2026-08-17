package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPostExtractionMergedColumnRecoveryTest {

    @Test
    void recoversCardinalitySafeCollapsedDefinitionFromRetainedRawCells() {
        ColumnDefinition source = column(
                "NormalMaxAmnt CodedMinAmnt CodedMaxAmnt",
                "big big big",
                "✓✓ ✓",
                "",
                List.of("", "NormalMaxAmnt CodedMinAmnt CodedMaxAmnt", "big big big", "",
                        "", "", "✓✓ ✓", "", "", ""));
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> recovered = LegacyPostExtractionMergedColumnRecovery.recover(
                List.of(source), warnings);

        assertEquals(3, recovered.size());
        assertEquals("NormalMaxAmnt", recovered.get(0).fieldName());
        assertEquals("CodedMinAmnt", recovered.get(1).fieldName());
        assertEquals("CodedMaxAmnt", recovered.get(2).fieldName());
        assertTrue(recovered.stream().allMatch(column -> Boolean.TRUE.equals(column.mandatory())));
        assertTrue(warnings.stream().anyMatch(warning ->
                "POST_EXTRACT_FLAT_MERGED_DEFINITION_ROW_SPLIT".equals(warning.code())));
    }

    @Test
    void refusesAmbiguousMandatoryOrReferenceOwnership() {
        ColumnDefinition oneMandatoryForTwoFields = column(
                "DbBranch SupportDbBranch",
                "int int",
                "✓",
                "",
                List.of("", "DbBranch SupportDbBranch", "int int", "", "", "", "✓", "", "", ""));
        ColumnDefinition combinedReferenceText = column(
                "SettleFlag StmtCredit",
                "tin big",
                "✓ ✓",
                "enum text",
                List.of("", "SettleFlag StmtCredit", "tin big", "", "", "", "✓ ✓", "", "", "enum text"));

        assertEquals(1, LegacyPostExtractionMergedColumnRecovery.recover(
                List.of(oneMandatoryForTwoFields), new ArrayList<>()).size());
        assertEquals(1, LegacyPostExtractionMergedColumnRecovery.recover(
                List.of(combinedReferenceText), new ArrayList<>()).size());
    }

    private static ColumnDefinition column(
            String fieldNameRaw,
            String typeRaw,
            String mandatoryRaw,
            String referenceRaw,
            List<String> rawCells) {
        return new ColumnDefinition(
                1, 0, 19, "",
                fieldNameRaw.replace(" ", ""), fieldNameRaw,
                typeRaw, "", "", "", mandatoryRaw, null,
                "", "", referenceRaw, List.of(), List.of(), rawCells);
    }
}
