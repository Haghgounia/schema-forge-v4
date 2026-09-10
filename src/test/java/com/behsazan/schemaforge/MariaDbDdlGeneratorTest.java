package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
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

/** M4 logical/physical DDL contract for MariaDB. */
class MariaDbDdlGeneratorTest {

    @Test
    void generatesCoreMariaDbDdlWithoutCrossDbmsSyntaxLeakage() {
        Column id = new Column(Identifier.of("CUSTOMER_ID"), DataType.numeric("NUMBER", 18, 0),
                false, new DefaultValue("CRM.SEQ_CUSTOMERS.NEXTVAL"),
                new Description("Customer identifier"), true, 1);
        Column code = new Column(Identifier.of("CUSTOMER_CODE"), DataType.varchar("VARCHAR2", 30),
                false, null, new Description("Customer's code"), false, 2);
        Column status = new Column(Identifier.of("STATUS"), DataType.numeric("NUMBER", 1, 0),
                false, new DefaultValue("1"), Description.empty(), false, 3);
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
                .addUniqueKey(new UniqueKey(Identifier.of("SOURCE_UQ_NAME"), List.of(Identifier.of("CUSTOMER_CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("SOURCE_CHECK_NAME"), "STATUS IN (0, 1)"))
                .addForeignKey(new ForeignKey(Identifier.of("SOURCE_FK_NAME"),
                        List.of(Identifier.of("BRANCH_ID")), QualifiedName.of("CRM", "BRANCHES"),
                        List.of(Identifier.of("BRANCH_ID")), ReferentialAction.CASCADE, ReferentialAction.RESTRICT))
                .addIndex(new Index(Identifier.of("SOURCE_INDEX_NAME"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.DESC)),
                        IndexType.NORMAL, Description.empty()))
                .physicalOption("GRANTS", "SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER")
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("CRM")
                .metadata("sourceFile", "CUSTOMERS.docx")
                .addSequence(new Sequence(QualifiedName.of("CRM", "SEQ_CUSTOMERS"), 1, 1,
                        null, null, false, null, Description.empty()))
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new MariaDbDialect()).generate(schema);

        assertTrue(sql.contains("SchemaForge Offline MariaDB DDL"));
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS `CRM`;"));
        assertFalse(sql.contains("CREATE SEQUENCE `CRM`.`SEQ_CUSTOMERS`"));
        assertTrue(sql.contains("`CUSTOMER_ID` BIGINT AUTO_INCREMENT NOT NULL COMMENT 'Customer identifier'"));
        assertTrue(sql.contains("`CUSTOMER_CODE` VARCHAR(30) NOT NULL COMMENT 'Customer''s code'"));
        assertTrue(sql.contains("`CREATED_AT` DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL"));
        assertTrue(sql.contains("`EFFECTIVE_STATUS` DECIMAL(1) GENERATED ALWAYS AS (COALESCE(STATUS, 0)) STORED"));
        assertTrue(sql.contains("CONSTRAINT `PK_CUSTOMERS` PRIMARY KEY (`CUSTOMER_ID`)"));
        assertTrue(sql.contains("ADD CONSTRAINT `UK_CUSTOMERS_CUSTOMER_CODE` UNIQUE(`CUSTOMER_CODE`);"));
        assertTrue(sql.contains("ADD CONSTRAINT `CHK_CUSTOMERS_STATUS` CHECK(STATUS IN (0, 1));"));
        assertTrue(sql.contains("ADD CONSTRAINT `FK_CUSTOMERS_BRANCH_ID` FOREIGN KEY (`BRANCH_ID`)"
                + " REFERENCES `CRM`.`BRANCHES`(`BRANCH_ID`) ON DELETE CASCADE ON UPDATE RESTRICT;"));
        assertTrue(sql.contains("CREATE INDEX `IX_CUSTOMERS_STATUS` ON `CRM`.`CUSTOMERS`(`STATUS` DESC)"));
        assertTrue(sql.contains("-- MARIADB TABLE PHYSICAL OPTIONS"));
        assertTrue(sql.contains("-- MARIADB INDEX PHYSICAL OPTIONS"));
        assertTrue(sql.contains("COMMENT='Customer''s master';"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON `CRM`.`CUSTOMERS` TO U_DEVELOPER;"));
        assertFalse(sql.contains("VARCHAR2"));
        assertFalse(sql.contains("NUMBER("));
        assertFalse(sql.contains("SYSDATE"));
        assertFalse(sql.contains(".NEXTVAL"));
        assertFalse(sql.contains("-- MYSQL TABLE PHYSICAL OPTIONS"));
    }

    @Test
    void emitsStandaloneMariaDbSequenceAndMapsNextValueDefault() {
        Sequence sequence = new Sequence(QualifiedName.of("APP", "SEQ_EXTERNAL"), 10, 2,
                10L, 1000L, false, 20, Description.empty());
        Column id = new Column(Identifier.of("ID"), DataType.simple("BIGINT"), false,
                new DefaultValue("APP.SEQ_EXTERNAL.NEXTVAL"), Description.empty(), false, 1);
        Table table = Table.builder("APP", "EXTERNAL_NUMBER")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PK_EXTERNAL_NUMBER"), List.of(Identifier.of("ID"))))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addSequence(sequence)
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new MariaDbDialect()).generate(schema);

        assertTrue(sql.contains("CREATE SEQUENCE `APP`.`SEQ_EXTERNAL` START WITH 10 INCREMENT BY 2"
                + " MAXVALUE 1000 MINVALUE 10 CACHE 20 NOCYCLE;"));
        assertTrue(sql.contains("`ID` BIGINT DEFAULT (NEXT VALUE FOR APP.SEQ_EXTERNAL) NOT NULL"));
    }

    @Test
    void rejectsDirectExpressionIndexInsteadOfPretendingMySqlCompatibility() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 30)))
                .addIndex(new Index(Identifier.of("SOURCE_EXPRESSION_INDEX"),
                        List.of(IndexColumn.expression("LOWER(CODE)")),
                        IndexType.NORMAL, Description.empty()))
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> new DdlGenerator(new MariaDbDialect()).generate(
                        DatabaseSchema.builder("APP").addTable(table).build()));
    }
}
