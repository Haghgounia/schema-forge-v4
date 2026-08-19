package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the first complete MySQL logical DDL generation path. */
class MySqlDdlGeneratorTest {

    @Test
    void shouldGenerateCoreMySqlDdlAndSuppressIdentityBackingSequence() {
        Column id = new Column(Identifier.of("CUSTOMER_ID"), DataType.numeric("NUMBER", 18, 0),
                false, new DefaultValue("CRM.SEQ_CUSTOMERS.NEXTVAL"),
                new Description("Customer identifier"), true, 1);
        Column code = new Column(Identifier.of("CUSTOMER_CODE"), DataType.varchar("VARCHAR2", 30),
                false, null, new Description("Customer's code"), false, 2);
        Column status = new Column(Identifier.of("STATUS"), DataType.numeric("NUMBER", 1, 0),
                false, new DefaultValue("1"), new Description("Status"), false, 3);
        Column createdAt = new Column(Identifier.of("CREATED_AT"), DataType.simple("DATE"),
                false, new DefaultValue("SYSDATE"), Description.empty(), false, 4);
        Column effectiveStatus = new Column(Identifier.of("EFFECTIVE_STATUS"), DataType.numeric("NUMBER", 1, 0),
                true, null, Description.empty(), false, 5, "NVL(STATUS, 0)");
        Column branchId = new Column(Identifier.of("BRANCH_ID"), DataType.numeric("NUMBER", 10, 0),
                true, null, Description.empty(), false, 6);

        Table table = Table.builder("CRM", "CUSTOMERS")
                .description("Customer's master")
                .addColumn(id)
                .addColumn(code)
                .addColumn(status)
                .addColumn(createdAt)
                .addColumn(effectiveStatus)
                .addColumn(branchId)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMERS_CODE"), List.of(Identifier.of("CUSTOMER_CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMERS_STATUS"), "STATUS IN (0, 1)"))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMERS_BRANCH"),
                        List.of(Identifier.of("BRANCH_ID")), QualifiedName.of("CRM", "BRANCHES"),
                        List.of(Identifier.of("BRANCH_ID")), ReferentialAction.CASCADE, ReferentialAction.RESTRICT))
                .addIndex(new Index(Identifier.of("IX_CUSTOMERS_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.DESC)),
                        IndexType.NORMAL, Description.empty()))
                .physicalOption("TABLESPACE", "ORACLE_DATA_TS")
                .physicalOption("INDEX_TABLESPACE", "ORACLE_INDEX_TS")
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("CRM")
                .metadata("sourceFile", "CUSTOMERS.docx")
                .addSequence(new Sequence(QualifiedName.of("CRM", "SEQ_CUSTOMERS"), 1, 1,
                        null, null, false, null, Description.empty()))
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new MySqlDialect()).generate(schema);

        assertTrue(sql.contains("SchemaForge Offline MySQL DDL"));
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS `CRM`;"));
        assertFalse(sql.contains("CREATE SEQUENCE"));
        assertTrue(sql.contains("`CUSTOMER_ID` BIGINT AUTO_INCREMENT NOT NULL COMMENT 'Customer identifier'"));
        assertTrue(sql.contains("`CUSTOMER_CODE` VARCHAR(30) NOT NULL COMMENT 'Customer''s code'"));
        assertTrue(sql.contains("`CREATED_AT` DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL"));
        assertTrue(sql.contains("`EFFECTIVE_STATUS` DECIMAL(1) GENERATED ALWAYS AS (COALESCE(STATUS, 0)) STORED"));
        assertTrue(sql.contains("CONSTRAINT `PK_CUSTOMERS` PRIMARY KEY (`CUSTOMER_ID`)"));
        assertTrue(sql.contains("ADD CONSTRAINT `UK_CUSTOMERS_CODE` UNIQUE(`CUSTOMER_CODE`);"));
        assertTrue(sql.contains("ADD CONSTRAINT `CK_CUSTOMERS_STATUS` CHECK(STATUS IN (0, 1));"));
        assertTrue(sql.contains("ADD CONSTRAINT `FK_CUSTOMERS_BRANCH` FOREIGN KEY (`BRANCH_ID`)"
                + " REFERENCES `CRM`.`BRANCHES`(`BRANCH_ID`) ON DELETE CASCADE ON UPDATE RESTRICT;"));
        assertTrue(sql.contains("CREATE INDEX `IX_CUSTOMERS_STATUS` ON `CRM`.`CUSTOMERS`(`STATUS` DESC);"));
        assertTrue(sql.contains(") COMMENT='Customer''s master';"));
        assertFalse(sql.contains("ORACLE_DATA_TS"));
        assertFalse(sql.contains("ORACLE_INDEX_TS"));
        assertTrue(sql.contains("Sequences    : 0"));
        assertTrue(sql.contains("Dialect      : MySQL"));
    }

    @Test
    void shouldRejectStandaloneSequenceBecauseMySqlHasNoSequenceObject() {
        Column id = new Column(Identifier.of("ID"), DataType.simple("BIGINT"),
                false, null, Description.empty(), false, 1);
        Table table = Table.builder("APP", "T")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PK_T"), List.of(Identifier.of("ID"))))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addSequence(new Sequence(QualifiedName.of("APP", "SEQ_EXTERNAL"), 1, 1,
                        null, null, false, null, Description.empty()))
                .addTable(table)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> new DdlGenerator(new MySqlDialect()).generate(schema));
    }

    @Test
    void shouldRejectAutoIncrementWithoutLeftmostIndexEvidence() {
        Column id = new Column(Identifier.of("ID"), DataType.simple("BIGINT"),
                false, null, Description.empty(), true, 1);
        Column code = new Column(Identifier.of("CODE"), DataType.varchar("VARCHAR2", 20),
                false, null, Description.empty(), false, 2);
        Table table = Table.builder("APP", "T")
                .addColumn(id)
                .addColumn(code)
                .primaryKey(new PrimaryKey(Identifier.of("PK_T"), List.of(Identifier.of("CODE"))))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new DdlGenerator(new MySqlDialect()).generate(
                        DatabaseSchema.builder("APP").addTable(table).build()));
    }
}
