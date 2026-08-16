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
        assertTrue(db2.contains("-- DB2/ZOS TABLE OPTIONS"));
        assertTrue(db2.contains("-- DB2/ZOS INDEX PHYSICAL OPTIONS"));
        assertTrue(db2.contains("FREEPAGE 0"));
        assertTrue(db2.contains("PCTFREE 10"));
        assertTrue(db2.contains("BUFFERPOOL <BUFFERPOOL>"));
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
