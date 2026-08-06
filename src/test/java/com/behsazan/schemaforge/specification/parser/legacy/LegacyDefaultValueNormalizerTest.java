package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDefaultValueNormalizerTest {
    private final LegacyDefaultValueNormalizer normalizer = new LegacyDefaultValueNormalizer();

    @Test
    void removesLegacyExplanatoryTextAfterNumericDefaults() {
        assertEquals("0", normalize("0 1- دائم 2- موقت", DataType.numeric("NUMBER", 1, 0)).expression());
        assertEquals("1", normalize("1 1- فعال 0- غیرفعال", DataType.numeric("NUMBER", 1, 0)).expression());
        assertEquals("0", normalize("0 CTShahabInquiry", DataType.numeric("NUMBER", 16, 0)).expression());
    }

    @Test
    void normalizesQuotesAndCurrentTimestampPhrases() {
        assertEquals("''", normalize("‘’ می باشد", DataType.varchar("VARCHAR2", 20)).expression());
        assertEquals("CURRENT_TIMESTAMP",
                normalize("زمان جاری سیستم", DataType.numeric("TIMESTAMP", 6, null)).expression());
        assertEquals("CURRENT_TIMESTAMP",
                normalize("WITH DEFAULT = CURRENT TIMESTAMP", DataType.numeric("TIMESTAMP", 6, null)).expression());
    }

    @Test
    void preservesConservativeSqlExpressions() {
        var numeric = normalize("-12.50", DataType.numeric("NUMBER", 10, 2));
        assertEquals("-12.50", numeric.expression());
        assertFalse(numeric.dropped());

        assertEquals("SEQ_CUSTOMER.NEXTVAL",
                normalize("SEQ_CUSTOMER.NEXTVAL", DataType.numeric("NUMBER", 19, 0)).expression());
        assertEquals("TO_DATE('2026-08-05','YYYY-MM-DD')",
                normalize("TO_DATE('2026-08-05','YYYY-MM-DD')", DataType.simple("DATE")).expression());
    }

    @Test
    void dropsUnsafeUnresolvedNaturalLanguageInsteadOfGeneratingInvalidSql() {
        var invalid = normalize(") ، 1 = فعال", DataType.numeric("NUMBER", 1, 0));
        assertNull(invalid.expression());
        assertTrue(invalid.dropped());

        var incompatible = normalize("تاریخ جاری سیستم", DataType.numeric("NUMBER", 8, 0));
        assertNull(incompatible.expression());
        assertTrue(incompatible.dropped());


        var unknownIdentifier = normalize("CTShahabInquiry", DataType.numeric("NUMBER", 16, 0));
        assertNull(unknownIdentifier.expression());
        assertTrue(unknownIdentifier.dropped());
    }


    @Test
    void rejectsDefaultsThatOracleCannotApplyToTheDeclaredType() {
        assertTrue(normalize("0", DataType.numeric("TIMESTAMP", 9, null)).dropped());
        assertTrue(normalize("CURRENT_TIMESTAMP", DataType.numeric("NUMBER", 8, 0)).dropped());
        assertTrue(normalize("' '", DataType.numeric("NUMBER", 8, 0)).dropped());
        assertTrue(normalize("- ' '", DataType.varchar("VARCHAR2", 25)).dropped());
        assertTrue(normalize("Decimal (18,3)", DataType.numeric("NUMBER", 18, 0)).dropped());
    }

    @Test
    void rejectsDefaultsThatExceedNumberPrecisionOrCharacterLength() {
        assertTrue(normalize("999", DataType.numeric("NUMBER", 2, 0)).dropped());
        assertTrue(normalize("100", DataType.numeric("NUMBER", 2, 0)).dropped());
        assertTrue(normalize("'111111111'", DataType.varchar("VARCHAR2", 8)).dropped());
        assertEquals("99", normalize("99", DataType.numeric("NUMBER", 2, 0)).expression());
        assertEquals("'12345678'", normalize("'12345678'", DataType.varchar("VARCHAR2", 8)).expression());
    }

    private LegacyDefaultValueNormalizer.Result normalize(String value, DataType type) {
        return normalizer.normalize(value, type);
    }
}
