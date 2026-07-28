package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the first complete Microsoft SQL Server DDL generation path. */
class SqlServerDdlGeneratorTest {

    @Test
    void shouldGenerateCoreSqlServerDdlFromCanonicalModel() {
        Column id = new Column(Identifier.of("CUSTOMER_ID"), DataType.numeric("NUMBER", 18, 0),
                false, null, new Description("Customer identifier"), true, 1);
        Column code = new Column(Identifier.of("CUSTOMER_CODE"), DataType.varchar("VARCHAR2", 30),
                false, null, new Description("Customer code"), false, 2);
        Column status = new Column(Identifier.of("STATUS"), DataType.numeric("NUMBER", 1, 0),
                false, new DefaultValue("1"), new Description("Status"), false, 3);
        Column createdAt = new Column(Identifier.of("CREATED_AT"), DataType.simple("DATE"),
                false, new DefaultValue("SYSDATE"), new Description("Creation time"), false, 4);
        Column sequenceId = new Column(Identifier.of("SEQUENCE_ID"), DataType.numeric("NUMBER", 18, 0),
                false, new DefaultValue("CRM.SEQ_CUSTOMERS.NEXTVAL"), Description.empty(), false, 5);
        Column effectiveStatus = new Column(Identifier.of("EFFECTIVE_STATUS"), DataType.numeric("NUMBER", 1, 0),
                false, null, Description.empty(), false, 6, "NVL(STATUS, 0)");

        Table table = Table.builder("crm", "customers")
                .description("Customer's master")
                .addColumn(id)
                .addColumn(code)
                .addColumn(status)
                .addColumn(createdAt)
                .addColumn(sequenceId)
                .addColumn(effectiveStatus)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMERS_CODE"), List.of(Identifier.of("CUSTOMER_CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMERS_STATUS"), "STATUS IN (0, 1)"))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMERS_BRANCH"),
                        List.of(Identifier.of("CUSTOMER_ID")), QualifiedName.of("CRM", "BRANCHES"),
                        List.of(Identifier.of("BRANCH_ID")), ReferentialAction.CASCADE, ReferentialAction.NO_ACTION))
                .addIndex(new Index(Identifier.of("IX_CUSTOMERS_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.DESC)),
                        IndexType.NORMAL, Description.empty(),
                        List.of(Identifier.of("CUSTOMER_CODE")), "STATUS = 1"))
                .physicalOption("TABLESPACE", "DATA_FG")
                .physicalOption("INDEX_TABLESPACE", "INDEX_FG")
                .physicalOption("GRANTS", "SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER")
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("CRM")
                .metadata("sourceFile", "CUSTOMERS.docx")
                .addSequence(new Sequence(QualifiedName.of("CRM", "SEQ_CUSTOMERS"), 1, 1,
                        null, null, false, 0, Description.empty()))
                .addTable(table)
                .build();

        String sql = new DdlGenerator(new SqlServerDialect()).generate(schema);

        assertTrue(sql.contains("SchemaForge Offline Microsoft SQL Server DDL"));
        assertTrue(sql.contains("SET XACT_ABORT ON;"));
        assertTrue(sql.contains("IF SCHEMA_ID(N'CRM') IS NULL "
                + "EXEC(N'CREATE SCHEMA CRM AUTHORIZATION [dbo]');"));
        assertTrue(sql.contains("CREATE SEQUENCE CRM.SEQ_CUSTOMERS START WITH 1 INCREMENT BY 1 NO CYCLE NO CACHE;"));
        assertTrue(sql.contains("CUSTOMER_ID DECIMAL(18,0) IDENTITY(1,1) NOT NULL"));
        assertTrue(sql.contains("CREATED_AT DATETIME2(0) DEFAULT SYSDATETIME() NOT NULL"));
        assertTrue(sql.contains("SEQUENCE_ID DECIMAL(18,0) DEFAULT NEXT VALUE FOR CRM.SEQ_CUSTOMERS NOT NULL"));
        assertTrue(sql.contains("EFFECTIVE_STATUS AS (COALESCE(STATUS, 0))"));
        assertFalse(sql.contains("EFFECTIVE_STATUS DECIMAL"));
        assertFalse(sql.contains("EFFECTIVE_STATUS AS (COALESCE(STATUS, 0)) NOT NULL"));
        assertTrue(sql.contains("CONSTRAINT PK_CUSTOMERS PRIMARY KEY (CUSTOMER_ID) ON INDEX_FG"));
        assertTrue(sql.contains(") ON DATA_FG;"));
        assertTrue(sql.contains("ADD CONSTRAINT UK_CUSTOMERS_CODE UNIQUE(CUSTOMER_CODE) ON INDEX_FG;"));
        assertTrue(sql.contains("REFERENCES CRM.BRANCHES(BRANCH_ID) ON DELETE CASCADE;"));
        assertTrue(sql.contains("CREATE INDEX IX_CUSTOMERS_STATUS ON CRM.CUSTOMERS(STATUS DESC)"
                + " INCLUDE (CUSTOMER_CODE) WHERE STATUS = 1 ON INDEX_FG;"));
        assertTrue(sql.contains("EXEC sys.sp_addextendedproperty @name=N'MS_Description',"
                + " @value=N'Customer''s master'"));
        assertTrue(sql.contains("@level2type=N'COLUMN', @level2name=N'CUSTOMER_CODE';"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE, DELETE ON CRM.CUSTOMERS TO U_DEVELOPER;"));
        assertTrue(sql.contains("Dialect      : SqlServer"));
        assertFalse(sql.contains("COMMENT ON TABLE"));
        assertFalse(sql.contains("CREATE INDEX CRM.IX_CUSTOMERS_STATUS"));
    }
}
