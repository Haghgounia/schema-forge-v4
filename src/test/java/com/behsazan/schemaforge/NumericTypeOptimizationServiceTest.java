package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericTypeOptimizationServiceTest {
    private static final NumericTypeOptimizationService.NumericIntegerProfile POSTGRESQL =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INTEGER", 9, "BIGINT", 18);

    private final NumericTypeOptimizationService optimizer = new NumericTypeOptimizationService();

    @Test
    void shouldChooseLosslessIntegerTypesByPrecision() {
        assertEquals("SMALLINT", optimizer.optimize(DataType.numeric("NUMBER", 4, 0), POSTGRESQL).orElseThrow());
        assertEquals("INTEGER", optimizer.optimize(DataType.numeric("NUMBER", 9, 0), POSTGRESQL).orElseThrow());
        assertEquals("BIGINT", optimizer.optimize(DataType.numeric("NUMBER", 18, 0), POSTGRESQL).orElseThrow());
    }

    @Test
    void shouldKeepDecimalOrUnboundedNumbersUnoptimized() {
        assertTrue(optimizer.optimize(DataType.numeric("NUMBER", 18, 2), POSTGRESQL).isEmpty());
        assertTrue(optimizer.optimize(DataType.simple("NUMBER"), POSTGRESQL).isEmpty());
        assertTrue(optimizer.optimize(DataType.numeric("NUMBER", 19, 0), POSTGRESQL).isEmpty());
    }
}
