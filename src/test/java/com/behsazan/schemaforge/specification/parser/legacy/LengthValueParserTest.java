package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LengthValueParserTest {
    @Test
    void parsesPrecisionAndScaleWithCommaOrDot() {
        var comma = LengthValueParser.parse("(25,5)");
        var dot = LengthValueParser.parse("20.5");

        assertEquals(25, comma.precision());
        assertEquals(5, comma.scale());
        assertEquals("25,5", comma.normalized());
        assertEquals(20, dot.precision());
        assertEquals(5, dot.scale());
        assertFalse(dot.ambiguous());
    }

    @Test
    void repairsDotBeforeCommaArtifact() {
        var parsed = LengthValueParser.parse("(23.,5)");

        assertEquals(23, parsed.precision());
        assertEquals(5, parsed.scale());
        assertEquals("23,5", parsed.normalized());
        assertFalse(parsed.ambiguous());
    }

    @Test
    void recoversConcatenatedTrackedRevision() {
        var parsed = LengthValueParser.parse("20,530,5");

        assertEquals("30,5", parsed.normalized());
        assertEquals(30, parsed.precision());
        assertEquals(5, parsed.scale());
        assertFalse(parsed.ambiguous());
    }

    @Test
    void collapsesThreeOrMoreSpacedSingleDigits() {
        assertEquals("100", LengthValueParser.parse("1 0 0").normalized());
        assertEquals("1000", LengthValueParser.parse("1 0 0 0").normalized());
        assertFalse(LengthValueParser.parse("1 0 0 0").ambiguous());
    }

    @Test
    void parsesPersianConjunctionAsPrecisionScaleSeparator() {
        var parsed = LengthValueParser.parse("20و5");
        assertEquals("20,5", parsed.normalized());
        assertEquals(20, parsed.precision());
        assertEquals(5, parsed.scale());
        assertFalse(parsed.ambiguous());
    }


    @Test
    void repairsSplitTrailingZeroGroups() {
        assertEquals("300", LengthValueParser.parse("3 00").normalized());
        assertEquals("1000", LengthValueParser.parse("100 0").normalized());
        assertFalse(LengthValueParser.parse("100 0").ambiguous());
    }

    @Test
    void parsesRtlParenthesesAndColonPrecisionScale() {
        var rtl = LengthValueParser.parse(")5,2)");
        var colon = LengthValueParser.parse("15:2");

        assertEquals("5,2", rtl.normalized());
        assertEquals(5, rtl.precision());
        assertEquals(2, rtl.scale());
        assertFalse(rtl.ambiguous());
        assertEquals("15,2", colon.normalized());
        assertEquals(15, colon.precision());
        assertEquals(2, colon.scale());
        assertFalse(colon.ambiguous());
    }

    @Test
    void keepsWhitespaceSeparatedNumbersAmbiguous() {
        assertTrue(LengthValueParser.parse("9 11").ambiguous());
    }
}
