package com.behsazan.schemaforge;

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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        Table customer = Table.builder("BIM", "CUSTOMERS")
                .description("Customer master")
                .addColumn(id)
                .addColumn(code)
                .addColumn(branchId)
                .addColumn(status)
                .addColumn(name)
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

        // 1. Sequence
        assertTrue(sql.contains("CREATE SEQUENCE BIM.SEQ_CUSTOMERS START WITH 1 INCREMENT BY 1"));
        assertTrue(sql.contains("MAXVALUE 999999999999999999 MINVALUE 1 NOCACHE NOCYCLE;"));

        // 2. Create table and columns
        assertTrue(sql.contains("CREATE TABLE BIM.CUSTOMERS"));
        assertTrue(sql.contains("CUSTOMER_ID NUMBER(18,0) NOT NULL"));
        assertTrue(sql.contains("NAME VARCHAR2(100 CHAR) DEFAULT 'UNKNOWN'"));
        assertTrue(sql.contains(") TABLESPACE TS_BIM;"));

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
        assertTrue(sql.contains("CREATE INDEX BIM.IDX_CUSTOMERS_NAME ON BIM.CUSTOMERS(NAME) TABLESPACE ITS_BIM;"));

        // 8. Comments (plus the already-supported grant and footer)
        assertTrue(sql.contains("-- Customer master"));
        assertTrue(sql.contains("COMMENT ON TABLE BIM.CUSTOMERS IS 'Customer master';"));
        assertTrue(sql.contains("COMMENT ON COLUMN BIM.CUSTOMERS.NAME IS 'Customer name';"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER ON BIM.CUSTOMERS;"));
        assertTrue(sql.contains("Generated On : 2026-07-24 21:00:45 +03:30"));
        assertTrue(sql.contains("Source File  : MCB.BIM.TBL.CUSTOMERS.V1.0.docx"));
        assertTrue(sql.contains("Dialect      : Oracle"));

        assertEquals(1, sql.lines().filter(line -> line.startsWith("CREATE TABLE ")).count());

        Path output = Path.of("target/test-output/BIM.CUSTOMERS.oracle.sql");
        Files.createDirectories(output.getParent());
        Files.writeString(output, sql);
        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);
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

        assertTrue(sql.contains("SCHEMAFORGE WARNING : DUPLICATE COLUMN DEFINITION"));
        assertTrue(sql.contains("PROMPT COLUMN              : IS_ACTIVE"));
        assertTrue(sql.contains("PROMPT FIRST WORD ROW      : 11"));
        assertTrue(sql.contains("PROMPT DUPLICATE WORD ROW  : 12"));
        assertTrue(sql.contains("-- IS_ACTIVE NUMBER(1) DEFAULT 1 NOT NULL;"));
        assertEquals(1, sql.lines().filter(line -> line.stripLeading().startsWith("IS_ACTIVE ")).count());
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
