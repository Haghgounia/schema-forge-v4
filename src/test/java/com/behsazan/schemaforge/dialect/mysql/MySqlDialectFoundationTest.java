package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDialectFoundationTest {
    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void shouldExposeActivatedLogicalCapabilitiesOnly() {
        assertTrue(dialect.supports(DialectFeature.IDENTITY_COLUMN));
        assertTrue(dialect.supports(DialectFeature.GENERATED_COLUMN));
        assertTrue(dialect.supports(DialectFeature.TABLE_COMMENT));
        assertTrue(dialect.supports(DialectFeature.COLUMN_COMMENT));
        assertTrue(dialect.supports(DialectFeature.EXPRESSION_INDEX));
        assertFalse(dialect.supports(DialectFeature.SEQUENCE));
        assertFalse(dialect.supports(DialectFeature.DEFERRABLE_CONSTRAINT));
        assertFalse(dialect.supports(DialectFeature.INDEX_INCLUDE));
        assertFalse(dialect.supports(DialectFeature.PARTIAL_INDEX));
    }

    @Test
    void shouldRenderIdentityAsAutoIncrementEvenWhenLegacyDefaultContainsNextval() {
        Column identity = new Column(
                Identifier.of("ID"), DataType.simple("BIGINT"), false,
                new DefaultValue("TSTSHMA.SEQ_PARTY.NEXTVAL"), Description.empty(),
                true, 1);
        assertEquals("BIGINT", dialect.sqlType(identity));
        assertEquals(" AUTO_INCREMENT", dialect.defaultClause(identity));
    }

    @Test
    void shouldUseLosslessBigIntForExactIdentityUpToEighteenDigits() {
        Column identity = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0), false,
                new DefaultValue("TSTSHMA.SEQ_PARTY.NEXTVAL"), Description.empty(),
                true, 1);
        assertEquals("BIGINT", dialect.sqlType(identity));
    }

    @Test
    void shouldRejectAutoIncrementWhenExactNumericRangeCannotBePreserved() {
        Column invalid = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 19, 0), false,
                null, Description.empty(), true, 1);
        assertThrows(IllegalArgumentException.class, () -> dialect.sqlType(invalid));
    }
}
