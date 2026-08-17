package com.behsazan.schemaforge;

import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;

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
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertColumnContains;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertColumnGeneratedOnce;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertHeaderContainsIssue;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertInlineIssues;

/**
 * Verifies the behavior and regression expectations of Oracle DDL Generator.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class OracleDdlGeneratorTest {

    @Test
    void shouldGenerateCompleteOraclePatchWithoutDatabaseConnection() throws Exception {
        Column id = column("CUSTOMER_ID", DataType.numeric("NUMBER", 18, 0), false, null,
                "Customer identifier", 1);
        Column code = column("CUSTOMER_CODE", DataType.varchar("VARCHAR", 20), false, null,
                "Customer code", 2);
        Column branchId = column("BRANCH_ID", DataType.numeric("NUMBER", 10, 0), false, null,
                "Branch identifier", 3);
        Column status = column("STATUS", DataType.numeric("NUMBER", 1, 0), false, "1",
                "Customer status", 4);
        Column name = column("NAME", DataType.varchar("VARCHAR", 100), true, "'UNKNOWN'",
                "Customer name", 5);
        Column sourceTimestamp = column("SOURCE_TIMESTAMP", DataType.numeric("TIMESTAMP", 10, null), true, null,
                "Source timestamp", 6);

        Table customer = Table.builder("BIM", "CUSTOMERS")
                .description("Customer master")
                .addColumn(id)
                .addColumn(code)
                .addColumn(branchId)
                .addColumn(status)
                .addColumn(name)
                .addColumn(sourceTimestamp)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_CUSTOMERS_CUSTOMER_ID"),
                        List.of(Identifier.of("CUSTOMER_ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_CUSTOMERS_CUSTOMER_CODE"),
                        List.of(Identifier.of("CUSTOMER_CODE"))))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_CUSTOMERS_BRANCH_ID"),
                        List.of(Identifier.of("BRANCH_ID")),
                        QualifiedName.of("BIM", "BRANCHES"),
                        List.of(Identifier.of("BRANCH_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .addCheck(new CheckConstraint(
                        Identifier.of("CHK_CUSTOMERS_STATUS"),
                        "STATUS IN (0, 1)"))
                .addIndex(new Index(
                        Identifier.of("IDX_CUSTOMERS_NAME"),
                        List.of(new IndexColumn(Identifier.of("NAME"), SortDirection.ASC)),
                        IndexType.NORMAL,
                        Description.empty()))
                .physicalOption("TABLESPACE", "TS_BIM")
                .physicalOption("INDEX_TABLESPACE", "ITS_BIM")
                .physicalOption("GRANTS", "SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER")
                .build();

        Sequence sequence = new Sequence(
                QualifiedName.of("BIM", "SEQ_CUSTOMERS"),
                1,
                1,
                1L,
                999999999999999999L,
                false,
                0,
                new Description("Customer sequence"));

        DatabaseSchema schema = DatabaseSchema.builder("CUSTOMER_SCHEMA")
                .metadata("sourceFile", "MCB.BIM.TBL.CUSTOMERS.V1.0.docx")
                .addSequence(sequence)
                .addTable(customer)
                .build();

        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-07-24T17:30:45Z"),
                ZoneOffset.ofHoursMinutes(3, 30));

        String sql = new DdlGenerator(new OracleDialect(), fixedClock).generate(schema);

        assertTrue(sql.contains("Oracle schema BIM is created by CREATE USER"));
        assertTrue(sql.contains("-- CREATE USER BIM IDENTIFIED BY \"<SECURE_PASSWORD>\""));

        // 1. Sequence
        assertTrue(sql.contains("CREATE SEQUENCE BIM.SEQ_CUSTOMERS START WITH 1 INCREMENT BY 1"));
        assertTrue(sql.contains("MAXVALUE 999999999999999999 MINVALUE 1 NOCACHE NOCYCLE NOORDER;"));

        // 2. Create table and columns
        assertTrue(sql.contains("CREATE TABLE BIM.CUSTOMERS"));
        assertTrue(sql.contains("CUSTOMER_ID NUMBER(18,0) NOT NULL"));
        assertTrue(sql.contains("NAME VARCHAR2(100 CHAR) DEFAULT 'UNKNOWN'"));
        assertTrue(sql.contains("SOURCE_TIMESTAMP TIMESTAMP(9)"));
        assertTrue(sql.contains("ORACLE_TEMPORAL_PRECISION_BOUNDED"));
        assertTrue(sql.contains("SOURCE_TIMESTAMP TIMESTAMP(9), -- W:TYPE-TIME"));
        assertTrue(sql.contains("TABLESPACE TS_BIM;"));
        assertTrue(sql.contains("-- ORACLE TABLE PHYSICAL OPTIONS"));
        assertTrue(sql.contains("PCTFREE 10"));
        assertTrue(sql.contains("INITRANS 1"));

        // 3. Primary key
        assertTrue(sql.contains("CONSTRAINT PK_CUSTOMERS_CUSTOMER_ID PRIMARY KEY (CUSTOMER_ID)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX BIM.PK_CUSTOMERS_CUSTOMER_ID ON BIM.CUSTOMERS(CUSTOMER_ID)"));
        assertTrue(sql.contains("TABLESPACE ITS_BIM"));

        // 4. Check constraint
        assertTrue(sql.contains("ALTER TABLE BIM.CUSTOMERS ADD CONSTRAINT CHK_CUSTOMERS_STATUS CHECK(STATUS IN (0, 1)) ENABLE;"));

        // 5. Unique constraint
        assertTrue(sql.contains("ALTER TABLE BIM.CUSTOMERS ADD CONSTRAINT UK_CUSTOMERS_CUSTOMER_CODE UNIQUE(CUSTOMER_CODE)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX BIM.UK_CUSTOMERS_CUSTOMER_CODE ON BIM.CUSTOMERS(CUSTOMER_CODE)"));

        // 6. Foreign key
        assertTrue(sql.contains("ALTER TABLE BIM.CUSTOMERS ADD CONSTRAINT FK_CUSTOMERS_BRANCH_ID FOREIGN KEY (BRANCH_ID) REFERENCES BIM.BRANCHES(BRANCH_ID) ENABLE;"));

        // 7. Index
        assertTrue(sql.contains("CREATE INDEX BIM.IDX_CUSTOMERS_NAME ON BIM.CUSTOMERS(NAME)"));
        assertTrue(sql.contains("-- ORACLE INDEX PHYSICAL OPTIONS"));
        assertTrue(sql.contains("TABLESPACE ITS_BIM;"));

        // 8. Comments (plus the already-supported grant and footer)
        assertTrue(sql.contains("-- Customer master"));
        assertTrue(sql.contains("COMMENT ON TABLE BIM.CUSTOMERS IS 'Customer master';"));
        assertTrue(sql.contains("COMMENT ON COLUMN BIM.CUSTOMERS.NAME IS 'Customer name';"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON BIM.CUSTOMERS TO U_DEVELOPER;"));
        assertTrue(sql.contains("Generated On : 2026-07-24 21:00:45 +03:30"));
        assertTrue(sql.contains("Source File  : MCB.BIM.TBL.CUSTOMERS.V1.0.docx"));
        assertTrue(sql.contains("Dialect      : Oracle"));

        assertEquals(1, sql.lines().filter(line -> line.startsWith("CREATE TABLE ")).count());

        OutputFileNamer fileNamer = new OutputFileNamer();
        Path output = Path.of("target/test-output").resolve(fileNamer.scriptFileName(
                "BIM.CUSTOMERS",
                DatabasePlatform.ORACLE,
                OutputFileNamer.ScriptKind.DDL,
                fileNamer.timestamp()));
        Files.createDirectories(output.getParent());
        Files.writeString(output, sql);
        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);
    }


    @Test
    void shouldUsePersianNameAsTableCommentAndKeepDescriptionAsHeader() {
        Table table = Table.builder("BIM", "CUSTOMERS")
                .persianName("مشتریان")
                .description("شرح کامل اطلاعات مشتریان")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 18, 0), false, null,
                        "شناسه مشتری", 1))
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("CUSTOMER_SCHEMA")
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new OracleDialect(), Clock.systemUTC()).generate(schema);

        assertTrue(sql.contains("-- Persian table name: مشتریان"));
        assertTrue(sql.contains("-- شرح کامل اطلاعات مشتریان"));
        assertTrue(sql.contains("COMMENT ON TABLE BIM.CUSTOMERS IS 'مشتریان';"));
        assertFalse(sql.contains("COMMENT ON TABLE BIM.CUSTOMERS IS 'شرح کامل اطلاعات مشتریان';"));
    }


    @Test
    void shouldUseNamedSequenceForLogicalIdentityColumns() {
        Column id = new Column(
                Identifier.of("DEPOSIT_PRODUCT_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false,
                null,
                new Description("Deposit product identifier"),
                true,
                1);
        Table table = Table.builder("DPS", "DEPOSIT_PRODUCT")
                .addColumn(id)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_DEPOSIT_PRODUCT"),
                        List.of(Identifier.of("DEPOSIT_PRODUCT_ID"))))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("DPS").addTable(table).build());

        assertTrue(sql.contains(
                "CREATE SEQUENCE DPS.SEQ_DEPOSIT_PRODUCT START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE NOORDER;"));
        assertTrue(sql.contains(
                "DEPOSIT_PRODUCT_ID NUMBER(19,0) DEFAULT DPS.SEQ_DEPOSIT_PRODUCT.NEXTVAL NOT NULL"));
        assertTrue(sql.contains("Sequences    : 1"));
        assertTrue(!sql.contains("GENERATED BY DEFAULT AS IDENTITY"));
    }

    @Test
    void shouldPreserveOracleCharacterLengthSemantics() {
        OracleDialect dialect = new OracleDialect();

        Column charColumn = column("CHAR_TEXT",
                DataType.varchar("VARCHAR2", 50, LengthSemantics.CHAR),
                true, null, "Character semantics", 1);
        Column byteColumn = column("BYTE_TEXT",
                DataType.varchar("VARCHAR2", 50, LengthSemantics.BYTE),
                true, null, "Byte semantics", 2);
        Column defaultColumn = column("DEFAULT_TEXT",
                new DataType(Identifier.of("VARCHAR2"), 50, LengthSemantics.DEFAULT, null, null),
                true, null, "Default semantics", 3);
        Column defaultCharColumn = column("DEFAULT_CHAR_TEXT",
                new DataType(Identifier.of("CHAR"), 3, LengthSemantics.DEFAULT, null, null),
                true, null, "Default CHAR semantics", 4);
        Column defaultNationalColumn = column("DEFAULT_NATIONAL_TEXT",
                new DataType(Identifier.of("NVARCHAR2"), 50, LengthSemantics.DEFAULT, null, null),
                true, null, "National character semantics", 5);

        assertEquals("VARCHAR2(50 CHAR)", dialect.sqlType(charColumn));
        assertEquals("VARCHAR2(50 BYTE)", dialect.sqlType(byteColumn));
        assertEquals("VARCHAR2(50 CHAR)", dialect.sqlType(defaultColumn));
        assertEquals("CHAR(3 CHAR)", dialect.sqlType(defaultCharColumn));
        assertEquals("NVARCHAR2(50)", dialect.sqlType(defaultNationalColumn));
    }

    @Test
    void shouldRenderDuplicateColumnWarningWithoutGeneratingDuplicateColumn() {
        Column isActive = column("IS_ACTIVE", DataType.numeric("NUMBER", 1, 0), false, "1",
                "Active flag", 1);

        Table table = Table.builder("BIM", "PROVINCES")
                .addColumn(isActive)
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .metadata("source.fileName", "MCB.BIM.TBL.PROVINCES.V1.1.docx")
                .metadata("recovery.warningCount", "1")
                .metadata("recovery.warnings",
                        "DUPLICATE_COLUMN|name=IS_ACTIVE|firstRow=11|duplicateRow=12|definition=IS_ACTIVE NUMBER(1) DEFAULT 1 NOT NULL")
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new OracleDialect(), Clock.fixed(
                Instant.parse("2026-07-24T17:30:45Z"),
                ZoneOffset.UTC)).generate(schema);

        assertTrue(sql.contains("SchemaForge Validation Findings"));
        assertHeaderContainsIssue(sql, "WARNING", "DUPLICATE_COLUMN",
                "tables.PROVINCES.columns.IS_ACTIVE");
        assertTrue(sql.contains("first Word row 11, duplicate Word row 12"));
        assertColumnContains(sql, "IS_ACTIVE", "NUMBER(1,0)", "DEFAULT 1", "NOT NULL");
        assertInlineIssues(sql, "IS_ACTIVE", "W", "DUP");
        assertColumnGeneratedOnce(sql, "IS_ACTIVE");
    }
    @Test
    void shouldApplyOracleSchemaBasedTablespaceDefaultsWhenOptionsAreAbsent() {
        Column id = column("DEPOSIT_ID", DataType.numeric("NUMBER", 9, 0), false, null,
                "Deposit identifier", 1);
        Column productId = column("DEPOSIT_PRODUCT_ID", DataType.numeric("NUMBER", 6, 0), false, null,
                "Deposit product identifier", 2);

        Table table = Table.builder("DPS", "DEPOSITS")
                .addColumn(id)
                .addColumn(productId)
                .primaryKey(new PrimaryKey(Identifier.of("PK_DEPOSITS_DEPOSIT_ID"),
                        List.of(Identifier.of("DEPOSIT_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_DEPOSITS_PRODUCT"),
                        List.of(Identifier.of("DEPOSIT_PRODUCT_ID"))))
                .addIndex(new Index(Identifier.of("IX_DEPOSITS_PRODUCT"),
                        List.of(new IndexColumn(Identifier.of("DEPOSIT_PRODUCT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("DPS").addTable(table).build());

        assertTrue(sql.contains("TABLESPACE TS_DPS;"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX DPS.PK_DEPOSITS_DEPOSIT_ID ON DPS.DEPOSITS(DEPOSIT_ID)"));
        assertTrue(sql.contains("TABLESPACE ITS_DPS"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX DPS.UK_DEPOSITS_PRODUCT ON DPS.DEPOSITS(DEPOSIT_PRODUCT_ID)"));
        assertFalse(sql.contains("CREATE INDEX DPS.IX_DEPOSITS_PRODUCT ON DPS.DEPOSITS(DEPOSIT_PRODUCT_ID)"));
    }



    @Test
    void shouldRenderOracleSafeReservedIdentifiersAndDropInvalidDefaults() {
        Table table = Table.builder("TSTSHMA", "USER")
                .addColumn(column("ROWID", DataType.numeric("NUMBER", 3, 0), false, null,
                        "Legacy row id", 1))
                .addColumn(column("DESC", DataType.varchar("VARCHAR2", 50), true, null,
                        "Legacy description", 2))
                .addColumn(column("TIMEX", DataType.numeric("TIMESTAMP", 9, null), true, "0",
                        "Invalid temporal default", 3))
                .addColumn(column("RESULTTYPE", DataType.numeric("NUMBER", 2, 0), true, "999",
                        "Invalid precision default", 4))
                .addColumn(column("PAYLOAD", DataType.varchar("VARCHAR2", 7000), true, null,
                        "Large payload", 5))
                .primaryKey(new PrimaryKey(Identifier.of("PK_USER"), List.of(Identifier.of("ROWID"))))
                .addIndex(new Index(Identifier.of("IX_USER_ROWID"),
                        List.of(new IndexColumn(Identifier.of("ROWID"), SortDirection.ASC),
                                new IndexColumn(Identifier.of("ROWID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build());

        assertTrue(sql.contains("CREATE TABLE TSTSHMA.SF_USER"));
        assertTrue(sql.contains("SF_ROWID NUMBER(3,0) NOT NULL"));
        assertTrue(sql.contains("SF_DESC VARCHAR2(50 CHAR)"));
        assertTrue(sql.contains("TIMEX TIMESTAMP(9)"));
        assertFalse(sql.contains("TIMEX TIMESTAMP(9) DEFAULT 0"));
        assertTrue(sql.contains("RESULTTYPE NUMBER(2,0)"));
        assertFalse(sql.contains("RESULTTYPE NUMBER(2,0) DEFAULT 999"));
        assertTrue(sql.contains("PAYLOAD CLOB"));
        assertTrue(sql.contains("PRIMARY KEY (SF_ROWID)"));
        assertFalse(sql.contains("CREATE INDEX TSTSHMA.IX_USER_ROWID"));
    }

    @Test
    void shouldDeduplicateRepeatedColumnsInsideStandaloneIndex() {
        Table table = Table.builder("TSTSHMA", "LOCK_CHQ")
                .addColumn(column("UNBLOCKID", DataType.numeric("NUMBER", 10, 0), true, null,
                        "Unblock id", 1))
                .addIndex(new Index(Identifier.of("IX_LOCK_CHQ"),
                        List.of(new IndexColumn(Identifier.of("UNBLOCKID"), SortDirection.ASC),
                                new IndexColumn(Identifier.of("UNBLOCKID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build());

        assertTrue(sql.contains("CREATE INDEX TSTSHMA.IX_LOCK_CHQ ON TSTSHMA.LOCK_CHQ(UNBLOCKID)"));
        assertFalse(sql.contains("UNBLOCKID,UNBLOCKID"));
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultExpression,
                                 String description, int ordinalPosition) {
        return new Column(
                Identifier.of(name),
                type,
                nullable,
                defaultExpression == null ? null : new DefaultValue(defaultExpression),
                new Description(description),
                false,
                ordinalPosition);
    }
}
