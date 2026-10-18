package com.behsazan.schemaforge.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampSemanticsTest {
    @Test
    void parsesSupportedRequestValuesAndDefaultsToSource() {
        assertEquals(TimestampSemantics.SOURCE, TimestampSemantics.parse(null));
        assertEquals(TimestampSemantics.SOURCE, TimestampSemantics.parse("  "));
        assertEquals(TimestampSemantics.WITHOUT_TIME_ZONE, TimestampSemantics.parse("without-time-zone"));
        assertEquals(TimestampSemantics.WITH_TIME_ZONE, TimestampSemantics.parse("WITH_TIME_ZONE"));
        assertEquals(TimestampSemantics.WITH_LOCAL_TIME_ZONE, TimestampSemantics.parse("with-local-time-zone"));
    }

    @Test
    void rejectsUnknownRequestValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TimestampSemantics.parse("utc"));
        assertTrue(exception.getMessage().contains("Unsupported timestampSemantics"));
    }
}
