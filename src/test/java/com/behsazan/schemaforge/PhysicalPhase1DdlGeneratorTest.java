package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
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

/** Regression coverage for the comment-only Physical Phase 1 contract. */
class PhysicalPhase1DdlGeneratorTest {

    @Test
    void shouldPreserveActivePlacementAndAddInlinePhysicalCommentsForAllDialects() {
        DatabaseSchema schema = schemaWithPlacementAndForeignKeys();

        String oracle = new DdlGenerator(new OracleDialect()).generate(schema);
        assertTrue(oracle.contains("TABLESPACE DATA_SPACE;"));
        assertTrue(oracle.contains("TABLESPACE INDEX_SPACE"));
        assertTrue(oracle.contains("-- ORACLE TABLE PHYSICAL OPTIONS"));
        assertTrue(oracle.contains("PCTFREE 10"));
        assertTrue(oracle.contains("INITRANS 1"));
        assertTrue(oracle.contains("-- ORACLE INDEX PHYSICAL OPTIONS"));

        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgresql.contains("TABLESPACE data_space;"));
        assertTrue(postgresql.contains("USING INDEX TABLESPACE index_space"));
        assertTrue(postgresql.contains("-- POSTGRESQL TABLE PHYSICAL OPTIONS"));
        assertTrue(postgresql.contains("WITH (fillfactor = 100)"));
        assertTrue(postgresql.contains("WITH (fillfactor = 90)"));

        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains(") ON DATA_SPACE"));
        assertTrue(sqlServer.contains("ON INDEX_SPACE"));
        assertTrue(sqlServer.contains("-- SQL SERVER TABLE PHYSICAL OPTIONS"));
        assertTrue(sqlServer.contains("DATA_COMPRESSION = NONE"));
        assertTrue(sqlServer.contains("FILLFACTOR = 0"));

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);
        assertTrue(db2.contains(") IN DATA_SPACE"));
        assertTrue(db2.contains("-- DB2/ZOS TABLE PHYSICAL OPTIONS"));
        assertTrue(db2.contains("-- DB2/ZOS INDEX PHYSICAL OPTIONS"));
        assertTrue(db2.contains("FREEPAGE 0"));
        assertTrue(db2.contains("PCTFREE 10"));
        assertTrue(db2.contains("BUFFERPOOL <BUFFERPOOL>"));
        assertFalse(db2.contains("AUDIT NONE"));
        assertFalse(db2.contains("DATA CAPTURE NONE"));
        assertFalse(db2.contains("WITH RESTRICT ON DROP"));
        assertFalse(db2.contains("CCSID UNICODE"));
        assertFalse(db2.contains("NOT VOLATILE"));
        assertFalse(db2.contains("APPEND NO"));
    }

    @Test
    void shouldKeepDb2DefaultsSourceDrivenAndUseMixedDataForCharVarchar() {
        Table table = Table.builder("ACC", "PHYSICAL_DEFAULT_TESTS")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("OPTIONAL_TEXT", DataType.varchar("VARCHAR2", 100), true, null, 2))
                .addColumn(column("EXPLICIT_DEFAULT", DataType.varchar("VARCHAR2", 10), true, "'X'", 3))
                .addColumn(column("CHAR_CODE", DataType.varchar("CHAR", 3), true, null, 4))
                .primaryKey(new PrimaryKey(Identifier.of("PK_PHYSICAL_DEFAULT_TESTS"), List.of(Identifier.of("ID"))))
                .build();

        String db2 = new DdlGenerator(new Db2ZosDialect())
                .generate(DatabaseSchema.builder("ACC").addTable(table).build());

        assertTrue(db2.contains("OPTIONAL_TEXT VARCHAR(100) FOR MIXED DATA"));
        assertTrue(db2.contains("CHAR_CODE CHAR(3) FOR MIXED DATA"));
        assertTrue(db2.contains("EXPLICIT_DEFAULT VARCHAR(10) FOR MIXED DATA WITH DEFAULT 'X'"));
        assertFalse(db2.contains("OPTIONAL_TEXT VARCHAR(100) FOR MIXED DATA WITH DEFAULT"));
        assertFalse(db2.contains("CHAR_CODE CHAR(3) FOR MIXED DATA WITH DEFAULT"));
    }

    @Test
    void shouldRecommendOnlyMissingForeignKeySupportingIndexesAndFlagVaryingDb2Keys() {
        DatabaseSchema schema = schemaWithPlacementAndForeignKeys();
        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);

        assertFalse(db2.contains("Foreign key FK_PARENT has no supporting index"));
        assertTrue(db2.contains("Foreign key FK_BIC has no supporting index"));
        assertTrue(db2.contains("<PADDED_OR_NOT_PADDED>"));
    }

    @Test
    void shouldRetainValidSourcePhysicalValuesInsideReviewablePhysicalBlocks() {
        Table table = Table.builder("ACC", "SOURCE_PHYSICAL_VALUES")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("CODE", DataType.varchar("VARCHAR2", 50), false, null, 2))
                .primaryKey(new PrimaryKey(Identifier.of("PK_SOURCE_PHYSICAL_VALUES"), List.of(Identifier.of("ID"))))
                .addIndex(new Index(Identifier.of("IX_SOURCE_PHYSICAL_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .physicalOption("ORACLE_PCTFREE", "25")
                .physicalOption("ORACLE_INDEX_INITRANS", "4")
                .physicalOption("ORACLE_INDEX_COMPRESSION", "COMPRESS ADVANCED HIGH")
                .physicalOption("ORACLE_TABLE_COMPRESSION", "ROW STORE COMPRESS ADVANCED")
                .physicalOption("POSTGRESQL_TABLE_FILLFACTOR", "80")
                .physicalOption("POSTGRESQL_TOAST_TUPLE_TARGET", "2040")
                .physicalOption("POSTGRESQL_INDEX_FILLFACTOR", "75")
                .physicalOption("POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "OFF")
                .physicalOption("SQLSERVER_TABLE_DATA_COMPRESSION", "ROW")
                .physicalOption("SQLSERVER_INDEX_FILLFACTOR", "85")
                .physicalOption("SQLSERVER_INDEX_IGNORE_DUP_KEY", "ON")
                .physicalOption("SQLSERVER_INDEX_STATISTICS_NORECOMPUTE", "ON")
                .physicalOption("SQLSERVER_INDEX_ALLOW_ROW_LOCKS", "OFF")
                .physicalOption("SQLSERVER_INDEX_ALLOW_PAGE_LOCKS", "OFF")
                .physicalOption("SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY", "ON")
                .physicalOption("DB2_INDEX_STOGROUP", "SGACC")
                .physicalOption("DB2_INDEX_PRIQTY", "-1")
                .physicalOption("DB2_INDEX_SECQTY", "0")
                .physicalOption("DB2_INDEX_FREEPAGE", "7")
                .physicalOption("DB2_INDEX_PCTFREE", "15")
                .physicalOption("DB2_INDEX_PIECESIZE", "1 G")
                .physicalOption("DB2_INDEX_PADDING", "NOT PADDED")
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("ACC").addTable(table).build();

        String oracle = new DdlGenerator(new OracleDialect()).generate(schema);
        assertTrue(oracle.contains("PCTFREE 25"));
        assertTrue(oracle.contains("INITRANS 4"));
        assertTrue(oracle.contains("ROW STORE COMPRESS ADVANCED"));
        assertTrue(oracle.contains("COMPRESS ADVANCED HIGH"));
        assertTrue(oracle.contains("[SOURCE PHYSICAL] ORACLE_PCTFREE=25"));

        Table basicCompressionTable = Table.builder("ACC", "BASIC_COMPRESSED")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .physicalOption("ORACLE_TABLE_COMPRESSION", "COMPRESS")
                .build();
        String basicCompressionOracle = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("ACC").addTable(basicCompressionTable).build());
        assertTrue(basicCompressionOracle.contains("PCTFREE 0"));
        assertTrue(basicCompressionOracle.contains("COMPRESS"));

        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgresql.contains("WITH (fillfactor = 80, toast_tuple_target = 2040)"));
        assertTrue(postgresql.contains("toast_tuple_target upper bound depends on server block size"));
        assertTrue(postgresql.contains("fillfactor = 75, deduplicate_items = off"));

        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains("WITH (DATA_COMPRESSION = ROW)"));
        assertTrue(sqlServer.contains("FILLFACTOR = 85"));
        assertTrue(sqlServer.contains("IGNORE_DUP_KEY = ON"));
        assertTrue(sqlServer.contains("IGNORE_DUP_KEY=ON is valid only for a UNIQUE index"));
        assertTrue(sqlServer.contains("STATISTICS_NORECOMPUTE = ON"));
        assertTrue(sqlServer.contains("ALLOW_ROW_LOCKS = OFF"));
        assertTrue(sqlServer.contains("ALLOW_PAGE_LOCKS = OFF"));
        assertTrue(sqlServer.contains("OPTIMIZE_FOR_SEQUENTIAL_KEY = ON"));

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);
        assertTrue(db2.contains("USING STOGROUP SGACC"));
        assertTrue(db2.contains("PRIQTY -1"));
        assertTrue(db2.contains("SECQTY 0"));
        assertTrue(db2.contains("FREEPAGE 7"));
        assertTrue(db2.contains("PCTFREE 15"));
        assertTrue(db2.contains("PIECESIZE 1 G"));
        assertTrue(db2.contains("PIECESIZE applicability/default depends on table-space size"));
        assertTrue(db2.contains("NOT PADDED"));
    }

    @Test
    void shouldSurfaceInvalidSourcePhysicalValuesWithoutSilentlyNormalizingThem() {
        Table table = Table.builder("ACC", "INVALID_SOURCE_PHYSICAL_VALUES")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("CODE", DataType.varchar("VARCHAR2", 50), false, null, 2))
                .primaryKey(new PrimaryKey(Identifier.of("PK_INVALID_SOURCE_PHYSICAL"), List.of(Identifier.of("ID"))))
                .addIndex(new Index(Identifier.of("IX_INVALID_SOURCE_PHYSICAL"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .physicalOption("ORACLE_PCTFREE", "120")
                .physicalOption("ORACLE_TABLE_COMPRESSION", "MAGIC")
                .physicalOption("ORACLE_INDEX_COMPRESSION", "COMPRESS 99")
                .physicalOption("POSTGRESQL_TABLE_FILLFACTOR", "5")
                .physicalOption("POSTGRESQL_TOAST_TUPLE_TARGET", "64")
                .physicalOption("POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "MAYBE")
                .physicalOption("SQLSERVER_TABLE_DATA_COMPRESSION", "MAGIC")
                .physicalOption("SQLSERVER_INDEX_ALLOW_ROW_LOCKS", "MAYBE")
                .physicalOption("DB2_INDEX_PRIQTY", "0")
                .physicalOption("DB2_INDEX_SECQTY", "-2")
                .physicalOption("DB2_INDEX_FREEPAGE", "999")
                .physicalOption("DB2_INDEX_PIECESIZE", "3 G")
                .physicalOption("DB2_INDEX_PADDING", "PADDED")
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("ACC").addTable(table).build();

        String oracle = new DdlGenerator(new OracleDialect()).generate(schema);
        assertTrue(oracle.contains("[SOURCE PHYSICAL ISSUE][ORACLE]"));
        assertTrue(oracle.contains("ORACLE_PCTFREE=120"));
        assertTrue(oracle.contains("PCTFREE <PCTFREE>"));
        assertTrue(oracle.contains("TABLE_COMPRESSION=MAGIC"));
        assertTrue(oracle.contains("<TABLE_COMPRESSION>"));
        assertTrue(oracle.contains("ORACLE_INDEX_COMPRESSION=COMPRESS 99"));
        assertTrue(oracle.contains("<INDEX_COMPRESSION>"));
        assertFalse(oracle.contains("PCTFREE 99"));

        Table unresolvedCompression = Table.builder("ACC", "ORACLE_UNKNOWN_COMPRESSION")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .physicalOption("ORACLE_TABLE_COMPRESSION", "MAGIC")
                .build();
        String unresolvedCompressionOracle = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("ACC").addTable(unresolvedCompression).build());
        assertTrue(unresolvedCompressionOracle.contains("Table compression is unresolved"));
        assertTrue(unresolvedCompressionOracle.contains("PCTFREE <PCTFREE>"));
        assertFalse(unresolvedCompressionOracle.contains("PCTFREE 10"));

        Table conflictingPercentages = Table.builder("ACC", "ORACLE_PCT_CONFLICT")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .physicalOption("ORACLE_PCTFREE", "70")
                .physicalOption("ORACLE_PCTUSED", "40")
                .build();
        String conflictOracle = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("ACC").addTable(conflictingPercentages).build());
        assertTrue(conflictOracle.contains("exceed Oracle's combined maximum of 100"));
        assertTrue(conflictOracle.contains("PCTFREE <PCTFREE>"));
        assertTrue(conflictOracle.contains("PCTUSED <PCTUSED>"));
        assertFalse(conflictOracle.contains("PCTFREE 70"));
        assertFalse(conflictOracle.contains("PCTUSED 40"));

        String postgresql = new DdlGenerator(new PostgreSqlDialect()).generate(schema);
        assertTrue(postgresql.contains("[SOURCE PHYSICAL ISSUE][POSTGRESQL]"));
        assertTrue(postgresql.contains("fillfactor = <TABLE_FILLFACTOR>"));
        assertTrue(postgresql.contains("toast_tuple_target = <TOAST_TUPLE_TARGET>"));
        assertTrue(postgresql.contains("TOAST_TUPLE_TARGET=64"));
        assertTrue(postgresql.contains("deduplicate_items = <INDEX_DEDUPLICATE_ITEMS>"));
        assertTrue(postgresql.contains("USING INDEX TABLESPACE <INDEX_TABLESPACE>"));
        assertTrue(postgresql.contains("TABLESPACE <INDEX_TABLESPACE>"));

        String sqlServer = new DdlGenerator(new SqlServerDialect()).generate(schema);
        assertTrue(sqlServer.contains("[SOURCE PHYSICAL ISSUE][SQLSERVER]"));
        assertTrue(sqlServer.contains("DATA_COMPRESSION = <TABLE_DATA_COMPRESSION>"));
        assertTrue(sqlServer.contains("ALLOW_ROW_LOCKS = <ALLOW_ROW_LOCKS>"));

        String db2 = new DdlGenerator(new Db2ZosDialect()).generate(schema);
        assertTrue(db2.contains("[SOURCE PHYSICAL ISSUE][DB2/ZOS]"));
        assertTrue(db2.contains("PRIQTY <PRIQTY>"));
        assertTrue(db2.contains("SECQTY <SECQTY>"));
        assertTrue(db2.contains("FREEPAGE <FREEPAGE>"));
        assertTrue(db2.contains("PIECESIZE <PIECESIZE>"));
        assertTrue(db2.contains("INDEX_PIECESIZE=3 G"));
        assertTrue(db2.contains("INDEX_PADDING=PADDED is irrelevant"));
        assertFalse(db2.contains("FREEPAGE 255"));
    }

    @Test
    void shouldUseSameOrderLeadingColumnsForCompositeForeignKeyCoverage() {
        Table table = Table.builder("ACC", "COMPOSITE_FK_TESTS")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("A", DataType.numeric("NUMBER", 10, 0), false, null, 2))
                .addColumn(column("B", DataType.numeric("NUMBER", 10, 0), false, null, 3))
                .addColumn(column("C", DataType.numeric("NUMBER", 10, 0), true, null, 4))
                .addColumn(column("X", DataType.numeric("NUMBER", 10, 0), false, null, 5))
                .addColumn(column("Y", DataType.numeric("NUMBER", 10, 0), false, null, 6))
                .primaryKey(new PrimaryKey(Identifier.of("PK_COMPOSITE_FK_TESTS"), List.of(Identifier.of("ID"))))
                .addIndex(new Index(Identifier.of("IX_ABC"), List.of(
                        new IndexColumn(Identifier.of("A"), SortDirection.ASC),
                        new IndexColumn(Identifier.of("B"), SortDirection.ASC),
                        new IndexColumn(Identifier.of("C"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addIndex(new Index(Identifier.of("IX_YX"), List.of(
                        new IndexColumn(Identifier.of("Y"), SortDirection.ASC),
                        new IndexColumn(Identifier.of("X"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_AB"),
                        List.of(Identifier.of("A"), Identifier.of("B")),
                        QualifiedName.of("ACC", "PARENTS_AB"),
                        List.of(Identifier.of("A"), Identifier.of("B")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .addForeignKey(new ForeignKey(Identifier.of("FK_XY"),
                        List.of(Identifier.of("X"), Identifier.of("Y")),
                        QualifiedName.of("ACC", "PARENTS_XY"),
                        List.of(Identifier.of("X"), Identifier.of("Y")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        String sql = new DdlGenerator(new OracleDialect())
                .generate(DatabaseSchema.builder("ACC").addTable(table).build());

        assertFalse(sql.contains("Foreign key FK_AB has no supporting index"));
        assertTrue(sql.contains("Foreign key FK_XY has no supporting index"));
    }

    private static DatabaseSchema schemaWithPlacementAndForeignKeys() {
        Table table = Table.builder("ACC", "PHYSICAL_TESTS")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("CODE", DataType.varchar("VARCHAR2", 50), false, null, 2))
                .addColumn(column("PARENT_ID", DataType.numeric("NUMBER", 10, 0), false, null, 3))
                .addColumn(column("BIC", DataType.varchar("VARCHAR2", 11), true, null, 4))
                .primaryKey(new PrimaryKey(Identifier.of("PK_PHYSICAL_TESTS"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_PHYSICAL_TESTS_CODE"), List.of(Identifier.of("CODE"))))
                .addIndex(new Index(Identifier.of("IX_PHYSICAL_TESTS_PARENT"),
                        List.of(new IndexColumn(Identifier.of("PARENT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_PARENT"), List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of("ACC", "PARENTS"), List.of(Identifier.of("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .addForeignKey(new ForeignKey(Identifier.of("FK_BIC"), List.of(Identifier.of("BIC")),
                        QualifiedName.of("ACC", "CORRESPONDENTS"), List.of(Identifier.of("BIC")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .physicalOption("TABLESPACE", "DATA_SPACE")
                .physicalOption("INDEX_TABLESPACE", "INDEX_SPACE")
                .build();
        return DatabaseSchema.builder("ACC").addTable(table).build();
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultValue, int position) {
        return new Column(Identifier.of(name), type, nullable,
                defaultValue == null ? null : new DefaultValue(defaultValue),
                Description.empty(), false, position);
    }
}
