package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
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
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSqlRendererTest {

    @Test
    void rendersOracleAndBlocksDestructiveDropByDefault() {
        String sql = render(DatabasePlatform.ORACLE);
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER MODIFY (NAME VARCHAR2(100 CHAR))"));
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER ADD MOBILE_NO VARCHAR2(20 CHAR)"));
        assertTrue(sql.contains("-- ALTER TABLE APP.CUSTOMER DROP COLUMN LEGACY_CODE;"));
        assertTrue(sql.contains("SchemaForge never infers column renames"));
    }

    @Test
    void rendersPostgreSqlColumnSyntax() {
        String sql = render(DatabasePlatform.POSTGRESQL);
        assertTrue(sql.contains("ALTER TABLE app.customer ALTER COLUMN name TYPE VARCHAR(100);"));
        assertTrue(sql.contains("ALTER TABLE app.customer ADD COLUMN mobile_no VARCHAR(20);"));
    }

    @Test
    void rendersDb2ZosColumnSyntax() {
        String sql = render(DatabasePlatform.DB2_ZOS);
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER ALTER COLUMN NAME SET DATA TYPE VARCHAR(100) FOR MIXED DATA;"));
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER ADD COLUMN MOBILE_NO VARCHAR(20) FOR MIXED DATA;"));
    }

    @Test
    void rendersSqlServerColumnSyntax() {
        String sql = render(DatabasePlatform.SQLSERVER);
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER ALTER COLUMN NAME VARCHAR(100) NULL;"));
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER ADD MOBILE_NO VARCHAR(20);"));
    }

    @Test
    void rendersMySqlCombinedModifyOnlyOncePerColumn() {
        Table live = liveTable();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.required("NAME", DataType.varchar("VARCHAR2", 100)))
                .addColumn(Column.nullable("MOBILE_NO", DataType.varchar("VARCHAR2", 20)))
                .build();
        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);
        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

        assertTrue(sql.contains("ALTER TABLE `APP`.`CUSTOMER` MODIFY COLUMN `NAME` VARCHAR(100) NOT NULL;"));
        assertEqualsOne(sql, "MODIFY COLUMN `NAME`");
        assertTrue(sql.contains("ALTER TABLE `APP`.`CUSTOMER` ADD COLUMN `MOBILE_NO` VARCHAR(20);"));
    }


    @Test
    void keepsUnsupportedMySqlDefaultAsCommentedReviewInsteadOfAbortingOutput() {
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .build();
        Column desiredId = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 10, 0), false,
                new DefaultValue("APP.SEQ_CUSTOMER.NEXTVAL"), Description.empty(), false, 1);
        Table desired = Table.builder("APP", "CUSTOMER").addColumn(desiredId).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);
        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

        assertTrue(sql.contains("BLOCKED: this REVIEW change cannot be rendered automatically for MYSQL"));
        assertTrue(sql.contains("does not map sequence NEXTVAL expressions"));
        assertTrue(sql.contains("CREATE output remains independent"));
    }

    @Test
    void rendersOwnedStructuralAddsForOracleAndMySql() {
        String oracle = renderStructuralAdds(DatabasePlatform.ORACLE);
        assertTrue(oracle.contains("ALTER TABLE APP.CUSTOMER ADD CONSTRAINT PK_CUSTOMER PRIMARY KEY"));
        assertTrue(oracle.contains("ADD CONSTRAINT UK_CUSTOMER_CODE UNIQUE(CODE)"));
        assertTrue(oracle.contains("ADD CONSTRAINT CK_CUSTOMER_STATUS CHECK("));
        assertTrue(oracle.contains("CREATE INDEX APP.IX_CUSTOMER_STATUS ON APP.CUSTOMER(STATUS)"));
        assertTrue(oracle.contains("ADD CONSTRAINT FK_CUSTOMER_PARENT FOREIGN KEY (PARENT_ID)"));

        String mysql = renderStructuralAdds(DatabasePlatform.MYSQL);
        assertTrue(mysql.contains("ALTER TABLE `APP`.`CUSTOMER` ADD CONSTRAINT `PK_CUSTOMER` PRIMARY KEY"));
        assertTrue(mysql.contains("ADD CONSTRAINT `UK_CUSTOMER_CODE` UNIQUE(`CODE`)"));
        assertTrue(mysql.contains("ADD CONSTRAINT `CK_CUSTOMER_STATUS` CHECK("));
        assertTrue(mysql.contains("CREATE INDEX `IX_CUSTOMER_STATUS` ON `APP`.`CUSTOMER`(`STATUS`)"));
        assertTrue(mysql.contains("ADD CONSTRAINT `FK_CUSTOMER_PARENT` FOREIGN KEY (`PARENT_ID`)"));
    }

    @Test
    void rendersPlatformSpecificStructuralDropsAndBlocksDestructiveConstraintsByDefault() {
        Table live = structuralTable();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .build();

        String mysql = new MigrationSqlRenderer().render(
                new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired),
                MigrationRenderOptions.safeDefaults());
        assertTrue(mysql.contains("-- ALTER TABLE `APP`.`CUSTOMER` DROP PRIMARY KEY;"));
        assertTrue(mysql.contains("-- ALTER TABLE `APP`.`CUSTOMER` DROP FOREIGN KEY `FK_CUSTOMER_PARENT`;"));
        assertTrue(mysql.contains("-- ALTER TABLE `APP`.`CUSTOMER` DROP INDEX `UK_CUSTOMER_CODE`;"));
        assertTrue(mysql.contains("-- ALTER TABLE `APP`.`CUSTOMER` DROP CHECK `CK_CUSTOMER_STATUS`;"));
        assertTrue(mysql.contains("ALTER TABLE `APP`.`CUSTOMER` DROP INDEX `IX_CUSTOMER_STATUS`;"));

        String sqlServer = new MigrationSqlRenderer().render(
                new SchemaDiffEngine().diff(DatabasePlatform.SQLSERVER, live, desired),
                MigrationRenderOptions.safeDefaults());
        assertTrue(sqlServer.contains("DROP INDEX IX_CUSTOMER_STATUS ON APP.CUSTOMER;"));
    }

    @Test
    void replacementAddRemainsBlockedWhenReplacementDropIsBlocked() {
        Table live = structuralTable();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"),
                        java.util.List.of(Identifier.of("ID"), Identifier.of("CODE"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_CODE"), java.util.List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMER_STATUS"), "STATUS IN ('A','I')"))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_STATUS"),
                        java.util.List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMER_PARENT"),
                        java.util.List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "CUSTOMER"),
                        java.util.List.of(Identifier.of("ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        String sql = new MigrationSqlRenderer().render(
                new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired),
                MigrationRenderOptions.safeDefaults());

        assertTrue(sql.contains("-- ALTER TABLE APP.CUSTOMER DROP CONSTRAINT PK_CUSTOMER;"));
        assertTrue(sql.contains("replacement ADD stays commented"));
        assertTrue(sql.contains("-- ALTER TABLE APP.CUSTOMER ADD CONSTRAINT PK_CUSTOMER PRIMARY KEY"));
    }

    @Test
    void destructiveSqlCanBeExplicitlyEnabled() {
        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, liveTable(), desiredTable());
        String sql = new MigrationSqlRenderer().render(plan, new MigrationRenderOptions(true));
        assertTrue(sql.contains("ALTER TABLE APP.CUSTOMER DROP COLUMN LEGACY_CODE;"));
        assertFalse(sql.contains("-- ALTER TABLE APP.CUSTOMER DROP COLUMN LEGACY_CODE;"));
    }

    private String renderStructuralAdds(DatabasePlatform platform) {
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .build();
        Table desired = structuralTable();
        return new MigrationSqlRenderer().render(
                new SchemaDiffEngine().diff(platform, live, desired),
                MigrationRenderOptions.safeDefaults());
    }

    private static Table structuralTable() {
        return Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), java.util.List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_CODE"), java.util.List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMER_STATUS"), "STATUS IN ('A','I')"))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_STATUS"),
                        java.util.List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMER_PARENT"),
                        java.util.List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "CUSTOMER"),
                        java.util.List.of(Identifier.of("ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
    }

    private String render(DatabasePlatform platform) {
        TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, liveTable(), desiredTable());
        return new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
    }

    private static Table liveTable() {
        return Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", 50)))
                .addColumn(Column.nullable("LEGACY_CODE", DataType.varchar("VARCHAR2", 20)))
                .build();
    }

    private static Table desiredTable() {
        return Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", 100)))
                .addColumn(Column.nullable("MOBILE_NO", DataType.varchar("VARCHAR2", 20)))
                .build();
    }

    private static void assertEqualsOne(String text, String needle) {
        long count = Stream.iterate(text.indexOf(needle), index -> index >= 0,
                        index -> text.indexOf(needle, index + needle.length()))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }
}
