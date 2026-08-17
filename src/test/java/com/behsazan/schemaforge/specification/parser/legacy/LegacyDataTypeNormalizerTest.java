package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Verifies conservative normalization of physical data-type evidence from legacy specifications.
 *
 * <p>The tests distinguish contextual abbreviations from unsupported tokens, reject index or
 * constraint text that leaked into type cells and ensure unknown physical types are reported
 * rather than guessed.</p>
 */
class LegacyDataTypeNormalizerTest {
    @ParameterizedTest
    @CsvSource({
            "T,TIMESTAMP",
            "DC,DECIMAL",
            "TD,TIMESTAMP",
            "DE,DECIMAL",
            "V,VARCHAR",
            "DT,TIMESTAMP",
            "B,SMALLINT",
            "L,SMALLINT",
            "F,DECIMAL",
            "TI,SMALLINT",
            "TIN,SMALLINT",
            "FLT,DECIMAL",
            "IMAGE,BLOB",
            "DATETIME,TIMESTAMP",
            "'D(5,2)','DECIMAL(5,2)'",
            "'DECIMAL (5,2)','DECIMAL(5,2)'",
            "'TIME STAMP',TIMESTAMP",
            "N,N",
            "C,C",
            "VAR,VARCHAR",
            "NVCHAR,NVARCHAR",
            "NVC,NVARCHAR"
    })
    void normalizesConfirmedAliasesWithoutGuessingLogicalTypes(String raw, String expected) {
        assertEquals(expected, LegacyDataTypeNormalizer.normalize(raw));
    }

    @Test
    void resolvesAmbiguousSOnlyInPhysicalDb2Context() {
        assertEquals("S", LegacyDataTypeNormalizer.normalize("S"));
        assertEquals("SMALLINT", LegacyDataTypeNormalizer.normalizeDb2("S"));
        assertEquals("SMALLINT", LegacyDataTypeNormalizer.normalizeDb2("SMALL"));
        assertEquals("CHAR", LegacyDataTypeNormalizer.normalizeDb2("C"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.db2TypeStatus("C"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.sourceTypeStatus("NVARCHAR"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.sourceTypeStatus("NVARCHAR2"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.sourceTypeStatus("NCHAR"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.sourceTypeStatus("VARCHAR2"));
    }

    @Test
    void rejectsIndexAndConstraintTokensFromPhysicalTypeColumn() {
        assertEquals("", LegacyDataTypeNormalizer.normalizeDb2("IX4"));
        assertEquals("", LegacyDataTypeNormalizer.normalizeDb2("IX1-5 IX1-6"));
        assertEquals("", LegacyDataTypeNormalizer.normalizeDb2("UIX"));
        assertEquals("", LegacyDataTypeNormalizer.normalizeDb2("PK1"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.INVALID_SOURCE_TOKEN,
                LegacyDataTypeNormalizer.db2TypeStatus("IX4"));
    }

    @Test
    void doesNotMisclassifyXmlAsLegacyXIndexToken() {
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.sourceTypeStatus("Xml"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.db2TypeStatus("XML"));
        assertEquals("XML", LegacyDataTypeNormalizer.normalizeDb2("XML"));
    }

    @Test
    void classifiesUnknownPhysicalTypeWithoutGuessing() {
        assertEquals("", LegacyDataTypeNormalizer.normalizeDb2("PARAMDESC"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.UNRELIABLE,
                LegacyDataTypeNormalizer.db2TypeStatus("PARAMDESC"));
        assertEquals(LegacyDataTypeNormalizer.TypeStatus.TRUSTED,
                LegacyDataTypeNormalizer.db2TypeStatus("TIMSTAMP"));
        assertEquals("TIMESTAMP", LegacyDataTypeNormalizer.normalizeDb2("TIMSTAMP"));
    }
}
