package com.behsazan.schemaforge.dialect.mariadb;

import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbDialectFoundationTest {
    private final MariaDbDialect dialect = new MariaDbDialect();

    @Test
    void shouldExposeMariaDbCapabilitiesWithoutPretendingToSupportDirectExpressionIndexes() {
        assertTrue(dialect.supports(DialectFeature.IDENTITY_COLUMN));
        assertTrue(dialect.supports(DialectFeature.GENERATED_COLUMN));
        assertTrue(dialect.supports(DialectFeature.TABLE_COMMENT));
        assertTrue(dialect.supports(DialectFeature.COLUMN_COMMENT));
        assertTrue(dialect.supports(DialectFeature.GRANT));
        assertTrue(dialect.supports(DialectFeature.SEQUENCE));
        assertFalse(dialect.supports(DialectFeature.EXPRESSION_INDEX));
        assertFalse(dialect.supports(DialectFeature.DEFERRABLE_CONSTRAINT));
        assertFalse(dialect.supports(DialectFeature.INDEX_INCLUDE));
        assertFalse(dialect.supports(DialectFeature.PARTIAL_INDEX));
    }

    @Test
    void shouldKeepLogicalIdentityOnAutoIncrementAndSuppressItsParserBackingSequence() {
        Column id = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0), false,
                new DefaultValue("APP.SEQ_ACCOUNT.NEXTVAL"), Description.empty(), true, 1);
        Table table = Table.builder("APP", "ACCOUNT")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PK_ACCOUNT"), List.of(Identifier.of("ID"))))
                .build();
        Sequence sequence = new Sequence(QualifiedName.of("APP", "SEQ_ACCOUNT"), 1, 1,
                null, null, false, null, Description.empty());
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addSequence(sequence)
                .addTable(table)
                .build();

        assertEquals("BIGINT", dialect.sqlType(id));
        assertEquals(" AUTO_INCREMENT", dialect.defaultClause(id));
        assertFalse(dialect.emitSequence(schema, sequence));
    }

    @Test
    void shouldEmitStandaloneSequenceAndUseItInAColumnDefault() {
        Sequence sequence = new Sequence(QualifiedName.of("APP", "SEQ_EXTERNAL"), 10, 2,
                10L, 1000L, false, 20, Description.empty());
        Column id = new Column(
                Identifier.of("ID"), DataType.simple("BIGINT"), false,
                new DefaultValue("APP.SEQ_EXTERNAL.NEXTVAL"), Description.empty(), false, 1);
        Table table = Table.builder("APP", "EXTERNAL_NUMBER")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PK_EXTERNAL_NUMBER"), List.of(Identifier.of("ID"))))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addSequence(sequence)
                .addTable(table)
                .build();

        assertTrue(dialect.emitSequence(schema, sequence));

        String sql = new DdlGenerator(dialect).generate(schema);
        assertTrue(sql.contains("CREATE SEQUENCE `APP`.`SEQ_EXTERNAL` START WITH 10 INCREMENT BY 2"
                + " MAXVALUE 1000 MINVALUE 10 CACHE 20 NOCYCLE;"));
        assertTrue(sql.contains("`ID` BIGINT DEFAULT (NEXT VALUE FOR APP.SEQ_EXTERNAL) NOT NULL"));
    }

    @Test
    void shouldRenderMariaDbSequenceCacheAndCycleSyntax() {
        assertEquals(" NOCACHE", dialect.sequenceCacheClause(null));
        assertEquals(" NOCACHE", dialect.sequenceCacheClause(0));
        assertEquals(" CACHE 50", dialect.sequenceCacheClause(50));
        assertEquals(" NOCYCLE", dialect.sequenceCycleClause(false));
        assertEquals(" CYCLE", dialect.sequenceCycleClause(true));
    }

    @Test
    void shouldApplyMariaDbSixtyFourCharacterPhysicalObjectLimit() {
        Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(
                dialect,
                Identifier.of("IX_THIS_IS_A_VERY_LONG_MARIADB_OBJECT_NAME_THAT_EXCEEDS_THE_SIXTY_FOUR_CHARACTER_LIMIT_AND_NEEDS_HASHING"));

        assertEquals(64, physical.value().length());
        assertTrue(physical.value().matches(".+_[0-9A-F]{12}"));
    }
}
