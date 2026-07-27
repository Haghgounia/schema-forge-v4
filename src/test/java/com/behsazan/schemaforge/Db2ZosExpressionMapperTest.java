package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosExpressionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects Oracle-oriented default and generated-expression mapping to Db2 for z/OS. */
class Db2ZosExpressionMapperTest {
    private final Db2ZosExpressionMapper mapper = new Db2ZosExpressionMapper();

    @Test
    void shouldMapSequenceAndDatetimeExpressions() {
        assertEquals("NEXT VALUE FOR BIM.SEQ_CUSTOMERS", mapper.map("BIM.SEQ_CUSTOMERS.NEXTVAL"));
        assertEquals("PREVIOUS VALUE FOR BIM.SEQ_CUSTOMERS", mapper.map("BIM.SEQ_CUSTOMERS.CURRVAL"));
        assertEquals("CURRENT TIMESTAMP(0)", mapper.map("SYSDATE"));
        assertEquals("CURRENT TIMESTAMP(12) WITH TIME ZONE", mapper.map("SYSTIMESTAMP"));
    }

    @Test
    void shouldMapNvlWithoutChangingOtherExpressionText() {
        assertEquals("COALESCE(STATUS, 0) + 1", mapper.map("NVL(STATUS, 0) + 1"));
    }
}
