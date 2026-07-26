package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlExpressionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the behavior and regression expectations of Postgre SQL Expression Mapper.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class PostgreSqlExpressionMapperTest {
    private final PostgreSqlExpressionMapper mapper = new PostgreSqlExpressionMapper();

    @Test
    void shouldMapOracleDateAndNullFunctions() {
        assertEquals("CURRENT_TIMESTAMP", mapper.map("SYSDATE"));
        assertEquals("CURRENT_TIMESTAMP", mapper.map("SYSTIMESTAMP"));
        assertEquals("COALESCE(STATUS, 0)", mapper.map("NVL(STATUS, 0)"));
    }

    @Test
    void shouldMapQualifiedAndUnqualifiedSequenceNextval() {
        assertEquals("nextval('seq_customer')", mapper.map("SEQ_CUSTOMER.NEXTVAL"));
        assertEquals("nextval('bim.seq_customer')", mapper.map("BIM.SEQ_CUSTOMER.NEXTVAL"));
    }
}
