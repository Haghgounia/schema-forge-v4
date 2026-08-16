package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
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

/**
 * Golden Physical Phase-1 regressions derived from real project table-design scenarios.
 *
 * <p>The fixtures intentionally avoid SPACE_FREE_NAME. They freeze the agreed physical
 * contract without depending on the Word parser, so a renderer/dialect regression can be
 * diagnosed independently from document-format variation.</p>
 */
class PhysicalPhase1GoldenCorpusTest {

    @Test
    void shouldFreezeVoucherTemplateHeaderRowsPhysicalContractAcrossAllDialects() {
        DatabaseSchema schema = DatabaseSchema.builder("ACC")
                .addTable(voucherTemplateHeaderRows())
                .build();

        String oracle = new DdlGenerator(new OracleDialect()).generate(schema);
        assertTrue(oracle.contains("TABLESPACE TS_ACC;"));
        assertTrue(oracle.contains("TABLESPACE ITS_ACC"));
        assertTrue(oracle.contains("-- ORACLE TABLE PHYSICAL OPTIONS"));
        assertTrue(oracle.contains("-- ORACLE INDEX PHYSICAL OPTIONS"));
        assertFalse(oracle.contains("<TABLE_TABLESPACE>"));
        assertFalse(oracle.contains("<INDEX_TABLESPACE>"));
        assertFalse(oracle.contains("SPACE_FREE_NAME"));

        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgresql.contains("-- POSTGRESQL TABLE PHYSICAL OPTIONS"));
        assertTrue(postgresql.contains("WITH (fillfactor = 100)"));
        assertTrue(postgresql.contains("TABLESPACE <TABLE_TABLESPACE>"));
        assertTrue(postgresql.contains("WITH (fillfactor = 90)"));
        assertTrue(postgresql.contains("TABLESPACE <INDEX_TABLESPACE>"));
        assertFalse(postgresql.contains("SPACE_FREE_NAME"));

        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains("-- SQL SERVER TABLE PHYSICAL OPTIONS"));
        assertTrue(sqlServer.contains("ON [<TABLE_FILEGROUP>]"));
        assertTrue(sqlServer.contains("DATA_COMPRESSION = NONE"));
        assertTrue(sqlServer.contains("ON [<INDEX_FILEGROUP>]"));
        assertFalse(sqlServer.contains("CLUSTERED"));
        assertFalse(sqlServer.contains("NONCLUSTERED"));
        assertFalse(sqlServer.contains("SPACE_FREE_NAME"));

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);
        assertTrue(db2.contains("VOUCHER_TEMPLATE_HEADER_ROW_NAME VARCHAR(255) FOR MIXED DATA NOT NULL"));
        assertTrue(db2.contains("IS_MANDATORY DECIMAL(1,0) WITH DEFAULT 0 NOT NULL"));
        assertTrue(db2.contains("IS_REPEATABLE DECIMAL(1,0) WITH DEFAULT 0 NOT NULL"));
        assertTrue(db2.contains("IS_ACTIVE DECIMAL(1,0) WITH DEFAULT 0 NOT NULL"));
        assertTrue(db2.contains("CREATE UNIQUE INDEX ACC.UK_VTHR_02_IX"));
        assertTrue(db2.contains("<PADDED_OR_NOT_PADDED>"));
        assertTrue(db2.contains("IN <DATABASE>.<TABLESPACE>"));
        assertFalse(db2.contains("SPACE_FREE_NAME"));
    }

    @Test
    void shouldFreezeCtmSourcePermissionDetailDefaultsAndFkSupportingIndexAnalysis() {
        DatabaseSchema schema = DatabaseSchema.builder("ACC")
                .addTable(sourcePermissionDetail())
                .build();

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);

        assertTrue(db2.contains("SOURCE_AMNT DECIMAL(20,5) WITH DEFAULT 0"));
        assertFalse(db2.contains("REQUEST_AMNT DECIMAL(20,5) WITH DEFAULT"));
        assertFalse(db2.contains("PERMIT_AMNT DECIMAL(20,5) WITH DEFAULT"));
        assertFalse(db2.contains("USED_AMNT DECIMAL(20,5) WITH DEFAULT"));

        assertFalse(db2.contains("Foreign key FK_SOURCE_PERMISSION has no supporting index"));
        assertTrue(db2.contains("Foreign key FK_CORRESPONDENT has no supporting index"));
        assertTrue(db2.contains("[PHYS-FK-INDEX-001]"));

        assertTrue(db2.contains("BIC VARCHAR(11) FOR MIXED DATA"));
        assertFalse(db2.contains("BIC VARCHAR(11) FOR MIXED DATA WITH DEFAULT"));
    }


    @Test
    void shouldKeepLobPlacementOutsidePhysicalPhase1AcrossAllDialects() {
        DatabaseSchema schema = DatabaseSchema.builder("CIF")
                .addTable(customerIdentifiersWithBlob())
                .build();

        String oracle = new DdlGenerator(new OracleDialect()).generate(schema);
        assertTrue(oracle.contains("CUSTOMER_IDENTIFIER_PHOTO BLOB NOT NULL"));
        assertTrue(oracle.contains("TABLESPACE TS_CIF;"));
        assertFalse(oracle.contains("LOB ("));
        assertFalse(oracle.contains("STORE AS"));

        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgresql.contains("customer_identifier_photo BYTEA NOT NULL"));
        assertFalse(postgresql.toUpperCase().contains("LOB TABLESPACE"));

        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains("CUSTOMER_IDENTIFIER_PHOTO VARBINARY(MAX) NOT NULL"));
        assertFalse(sqlServer.contains("TEXTIMAGE_ON"));

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);
        assertTrue(db2.contains("CUSTOMER_IDENTIFIER_PHOTO BLOB NOT NULL"));
        assertTrue(db2.contains("REMARKS VARCHAR(255) FOR MIXED DATA"));
        assertFalse(db2.toUpperCase().contains("AUXILIARY TABLE"));
        assertFalse(db2.toUpperCase().contains("LOB TABLESPACE"));
        assertFalse(db2.contains("SPACE_FREE_NAME"));
    }

    private static Table voucherTemplateHeaderRows() {
        return Table.builder("ACC", "VOUCHER_TEMPLATE_HEADER_ROWS")
                .addColumn(column("VOUCHER_TEMPLATE_HEADER_ROW_ID", DataType.numeric("NUMBER", 4, 0), false, null, true, 1))
                .addColumn(column("VOUCHER_TEMPLATE_HEADER_ID", DataType.numeric("NUMBER", 4, 0), false, null, false, 2))
                .addColumn(column("VOUCHER_TEMPLATE_HEADER_ROW_CODE", DataType.numeric("NUMBER", 4, 0), false, null, false, 3))
                .addColumn(column("VOUCHER_TEMPLATE_HEADER_ROW_NAME", DataType.varchar("VARCHAR2", 255), false, null, false, 4))
                .addColumn(column("VOUCHER_TEMPLATE_HEADER_ROW_ENGLISH_NAME", DataType.varchar("VARCHAR2", 255), true, null, false, 5))
                .addColumn(column("HEADER_ROW_SEQUENCE_NUMBER", DataType.numeric("NUMBER", 2, 0), false, null, false, 6))
                .addColumn(column("IS_MANDATORY", DataType.numeric("NUMBER", 1, 0), false, "0", false, 7))
                .addColumn(column("IS_REPEATABLE", DataType.numeric("NUMBER", 1, 0), false, "0", false, 8))
                .addColumn(column("ARTICLE_NATURE", DataType.numeric("NUMBER", 1, 0), false, null, false, 9))
                .addColumn(column("BALANCE_TYPE_ID", DataType.numeric("NUMBER", 4, 0), false, null, false, 10))
                .addColumn(column("VOUCHER_HEADER_DESC_DEFAULT", DataType.varchar("VARCHAR2", 255), false, null, false, 11))
                .addColumn(column("COMPUTATIONAL_FORMULA_ID", DataType.numeric("NUMBER", 4, 0), true, null, false, 12))
                .addColumn(column("REMARKS", DataType.varchar("VARCHAR2", 255), true, null, false, 13))
                .addColumn(column("IS_ACTIVE", DataType.numeric("NUMBER", 1, 0), false, "0", false, 14))
                .addColumn(column("VALIDITY_DATE", DataType.numeric("NUMBER", 8, 0), true, null, false, 15))
                .primaryKey(new PrimaryKey(Identifier.of("PK_VTHR"),
                        List.of(Identifier.of("VOUCHER_TEMPLATE_HEADER_ROW_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_VTHR_01"),
                        List.of(Identifier.of("VOUCHER_TEMPLATE_HEADER_ID"),
                                Identifier.of("VOUCHER_TEMPLATE_HEADER_ROW_CODE"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_VTHR_02"),
                        List.of(Identifier.of("VOUCHER_TEMPLATE_HEADER_ROW_NAME"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_VTHR_MANDATORY"), "IS_MANDATORY IN (0,1)"))
                .addCheck(new CheckConstraint(Identifier.of("CK_VTHR_REPEATABLE"), "IS_REPEATABLE IN (0,1)"))
                .addCheck(new CheckConstraint(Identifier.of("CK_VTHR_ARTICLE_NATURE"), "ARTICLE_NATURE IN (1,2)"))
                .addCheck(new CheckConstraint(Identifier.of("CK_VTHR_ACTIVE"), "IS_ACTIVE IN (0,1)"))
                .addIndex(new Index(Identifier.of("IX_VTHR_01"),
                        List.of(new IndexColumn(Identifier.of("VOUCHER_TEMPLATE_HEADER_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addIndex(new Index(Identifier.of("IX_VTHR_02"),
                        List.of(new IndexColumn(Identifier.of("VOUCHER_TEMPLATE_HEADER_ROW_CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();
    }


    private static Table customerIdentifiersWithBlob() {
        return Table.builder("CIF", "CUSTOMER_IDENTIFIERS")
                .addColumn(column("CUSTOMER_IDENTIFIER_ID", DataType.numeric("NUMBER", 10, 0), false, null, true, 1))
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, null, false, 2))
                .addColumn(column("PERSONAL_IDENTIFIER", DataType.numeric("NUMBER", 1, 0), false, null, false, 3))
                .addColumn(column("REMARKS", DataType.varchar("VARCHAR2", 255), true, null, false, 4))
                .addColumn(column("CUSTOMER_IDENTIFIER_PHOTO", DataType.simple("BLOB"), false, null, false, 5))
                .addColumn(column("IS_ACTIVE", DataType.numeric("NUMBER", 1, 0), false, "1", false, 6))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER_IDENTIFIERS"),
                        List.of(Identifier.of("CUSTOMER_IDENTIFIER_ID"))))
                .build();
    }

    private static Table sourcePermissionDetail() {
        return Table.builder("ACC", "CTM_SOURCE_PERMISSION_DETAIL")
                .addColumn(column("ID", DataType.numeric("NUMBER", 19, 0), false, null, true, 1))
                .addColumn(column("PERMIT_NO", DataType.numeric("NUMBER", 19, 0), false, null, false, 2))
                .addColumn(column("BIC", DataType.varchar("VARCHAR2", 11), true, null, false, 3))
                .addColumn(column("SOURCE_AMNT", DataType.numeric("NUMBER", 20, 5), true, "0", false, 4))
                .addColumn(column("REQUEST_AMNT", DataType.numeric("NUMBER", 20, 5), true, null, false, 5))
                .addColumn(column("PERMIT_AMNT", DataType.numeric("NUMBER", 20, 5), true, null, false, 6))
                .addColumn(column("USED_AMNT", DataType.numeric("NUMBER", 20, 5), true, null, false, 7))
                .primaryKey(new PrimaryKey(Identifier.of("PK_SOURCE_PERMISSION_DETAIL"),
                        List.of(Identifier.of("ID"))))
                .addIndex(new Index(Identifier.of("IX_SOURCE_PERMISSION_DETAIL_PERMIT"),
                        List.of(new IndexColumn(Identifier.of("PERMIT_NO"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_SOURCE_PERMISSION"),
                        List.of(Identifier.of("PERMIT_NO")),
                        QualifiedName.of("ACC", "CTM_SOURCE_PERMISSION"),
                        List.of(Identifier.of("PERMIT_NO")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CORRESPONDENT"),
                        List.of(Identifier.of("BIC")),
                        QualifiedName.of("ACC", "CTM_CORESPONDENT"),
                        List.of(Identifier.of("BIC")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
    }

    private static Column column(
            String name, DataType type, boolean nullable, String defaultValue, boolean identity, int position) {
        return new Column(Identifier.of(name), type, nullable,
                defaultValue == null ? null : new DefaultValue(defaultValue),
                Description.empty(), identity, position);
    }
}
