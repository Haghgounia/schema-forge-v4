package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.application.DialectFactory;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossDbmsDescriptionMigrationTest {

    @Test
    void detectsAndRendersTableAndColumnDescriptionChangesForEveryPlatform() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table live = table("Old table description", "Old column description");
            Table desired = table("New table description", "New column description");

            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);

            assertFalse(plan.empty(), platform.name());
            assertTrue(plan.tableDescriptionChanged(), platform.name());
            assertTrue(plan.columnChanges().stream().anyMatch(change ->
                    change.kind() == ColumnChangeKind.ALTER_DESCRIPTION), platform.name());

            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            switch (platform) {
                case ORACLE, DB2_ZOS, DB2_LUW -> {
                    assertTrue(sql.contains("COMMENT ON TABLE APP.CUSTOMER IS 'New table description';"), platform.name());
                    assertTrue(sql.contains("COMMENT ON COLUMN APP.CUSTOMER.CODE IS 'New column description';"), platform.name());
                }
                case POSTGRESQL -> {
                    assertTrue(sql.contains("COMMENT ON TABLE app.customer IS E'New table description';"), platform.name());
                    assertTrue(sql.contains("COMMENT ON COLUMN app.customer.code IS E'New column description';"), platform.name());
                }
                case SQLSERVER -> {
                    assertTrue(sql.contains("sp_updateextendedproperty"), platform.name());
                    assertTrue(sql.contains("sp_addextendedproperty"), platform.name());
                    assertTrue(sql.contains("New table description"), platform.name());
                    assertTrue(sql.contains("New column description"), platform.name());
                }
                case MYSQL, MARIADB -> {
                    assertTrue(sql.contains("ALTER TABLE `APP`.`CUSTOMER` COMMENT = 'New table description';"), platform.name());
                    assertTrue(sql.contains("MODIFY COLUMN `CODE` VARCHAR(30) COMMENT 'New column description';"), platform.name());
                }
            }
        }
    }

    @Test
    void addedColumnCarriesItsDescriptionInMigrationForEveryPlatform() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table live = Table.builder("APP", "CUSTOMER")
                    .addColumn(column("ID", "Identifier"))
                    .build();
            Table desired = Table.builder("APP", "CUSTOMER")
                    .addColumn(column("ID", "Identifier"))
                    .addColumn(column("CODE", "New code description"))
                    .build();

            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);
            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

            assertTrue(sql.contains("New code description"), platform.name());
        }
    }

    @Test
    void removingDescriptionProducesConvergentMetadataSql() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table live = table("Old table description", "Old column description");
            Table desired = table("", "");

            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);
            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

            assertFalse(plan.empty(), platform.name());
            if (platform == DatabasePlatform.SQLSERVER) {
                assertTrue(sql.contains("sp_dropextendedproperty"), platform.name());
            } else if (platform == DatabasePlatform.MYSQL || platform == DatabasePlatform.MARIADB) {
                assertTrue(sql.contains("COMMENT = '';"), platform.name());
                assertTrue(sql.contains("MODIFY COLUMN `CODE` VARCHAR(30);"), platform.name());
            } else {
                assertTrue(sql.contains("COMMENT ON TABLE"), platform.name());
                assertTrue(sql.contains("COMMENT ON COLUMN"), platform.name());
            }
        }
    }


    @Test
    void canonicalTableDescriptionWinsOverPersianNameForCreateCompareAndMigration() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table desired = Table.builder("APP", "CUSTOMER")
                    .persianName("نام فارسی جدول")
                    .description("Canonical table documentation")
                    .addColumn(column("CODE", "Column documentation"))
                    .build();
            Table live = Table.builder("APP", "CUSTOMER")
                    .description("نام فارسی جدول")
                    .addColumn(column("CODE", "Column documentation"))
                    .build();

            String createSql = new DdlGenerator(DialectFactory.create(platform))
                    .generate(com.behsazan.schemaforge.domain.model.DatabaseSchema.builder("APP").addTable(desired).build());
            assertTrue(createSql.contains("Canonical table documentation"), platform.name());

            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);
            assertTrue(plan.tableDescriptionChanged(), platform.name());
            String migrationSql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            assertTrue(migrationSql.contains("Canonical table documentation"), platform.name());
        }
    }

    @Test
    void unicodeQuotesAndLineBreaksAreEscapedWithoutLosingCommentContent() {
        String desiredComment = "شرح O'Reilly\nخط دوم فارسی";
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table live = table("Old table description", "Old column description");
            Table desired = table(desiredComment, desiredComment);

            String sql = new MigrationSqlRenderer().render(
                    new SchemaDiffEngine().diff(platform, live, desired),
                    MigrationRenderOptions.safeDefaults());

            assertTrue(sql.contains("O''Reilly"), platform.name());
            assertTrue(sql.contains("خط دوم فارسی"), platform.name());
        }
    }

    @Test
    void commentStateConvergesToZeroResidualForEveryPlatform() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table desired = table("نسخه جدید توضیح جدول", "نسخه جدید توضیح ستون");
            Table liveBefore = table("نسخه قبلی توضیح جدول", "نسخه قبلی توضیح ستون");
            TableMigrationPlan before = new SchemaDiffEngine().diff(platform, liveBefore, desired);
            assertFalse(before.empty(), platform.name());

            Table liveAfterReread = table("نسخه جدید توضیح جدول", "نسخه جدید توضیح ستون");
            TableMigrationPlan after = new SchemaDiffEngine().diff(platform, liveAfterReread, desired);
            assertTrue(after.empty(), "description residual must be zero for " + platform);
        }
    }

    @Test
    void mysqlAndMariaDbCommentOnlyModifyPreservesLivePhysicalDefinition() {
        for (DatabasePlatform platform : List.of(DatabasePlatform.MYSQL, DatabasePlatform.MARIADB)) {
            String prefix = platform == DatabasePlatform.MYSQL ? "MYSQL" : "MARIADB";
            Column liveColumn = new Column(
                    Identifier.of("CODE"), DataType.varchar("VARCHAR", 30), false,
                    new DefaultValue("'A'"), new Description("Old comment"), false, 1, null,
                    Map.of(
                            prefix + "_NATIVE_COLUMN_TYPE", "varchar(30)",
                            prefix + "_CHARACTER_SET", "utf8mb4",
                            prefix + "_COLLATION", "utf8mb4_persian_ci",
                            prefix + "_EXTRA", ""));
            Column desiredColumn = new Column(
                    Identifier.of("CODE"), DataType.varchar("VARCHAR", 30), false,
                    new DefaultValue("'A'"), new Description("New comment"), false, 1);
            Table liveTable = Table.builder("APP", "CUSTOMER").addColumn(liveColumn).build();
            Table desiredTable = Table.builder("APP", "CUSTOMER").addColumn(desiredColumn).build();
            TableMigrationPlan plan = new TableMigrationPlan(
                    platform, liveTable, desiredTable,
                    List.of(new ColumnChange(ColumnChangeKind.ALTER_DESCRIPTION, Identifier.of("CODE"),
                            liveColumn, desiredColumn, MigrationRisk.SAFE, "comment changes")),
                    List.of(), false);

            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            assertTrue(sql.contains("varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_persian_ci"), platform.name());
            assertTrue(sql.contains("DEFAULT 'A'"), platform.name());
            assertTrue(sql.contains("NOT NULL"), platform.name());
            assertTrue(sql.contains("COMMENT 'New comment'"), platform.name());
        }
    }

    @Test
    void mysqlAndMariaDbUnknownLiveExtraFailsClosedForCommentOnlyModify() {
        for (DatabasePlatform platform : List.of(DatabasePlatform.MYSQL, DatabasePlatform.MARIADB)) {
            String prefix = platform == DatabasePlatform.MYSQL ? "MYSQL" : "MARIADB";
            Column liveColumn = new Column(
                    Identifier.of("CODE"), DataType.varchar("VARCHAR", 30), true,
                    new DefaultValue(null), new Description("Old comment"), false, 1, null,
                    Map.of(prefix + "_NATIVE_COLUMN_TYPE", "varchar(30)", prefix + "_EXTRA", "INVISIBLE"));
            Column desiredColumn = new Column(
                    Identifier.of("CODE"), DataType.varchar("VARCHAR", 30), true,
                    new DefaultValue(null), new Description("New comment"), false, 1);
            Table liveTable = Table.builder("APP", "CUSTOMER").addColumn(liveColumn).build();
            Table desiredTable = Table.builder("APP", "CUSTOMER").addColumn(desiredColumn).build();
            TableMigrationPlan plan = new TableMigrationPlan(
                    platform, liveTable, desiredTable,
                    List.of(new ColumnChange(ColumnChangeKind.ALTER_DESCRIPTION, Identifier.of("CODE"),
                            liveColumn, desiredColumn, MigrationRisk.SAFE, "comment changes")),
                    List.of(), false);

            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            assertTrue(sql.contains("BLOCKED"), platform.name());
            assertFalse(sql.contains("MODIFY COLUMN `CODE`"), platform.name());
        }
    }

    private static Table table(String tableDescription, String columnDescription) {
        return Table.builder("APP", "CUSTOMER")
                .description(tableDescription)
                .addColumn(column("CODE", columnDescription))
                .build();
    }

    private static Column column(String name, String description) {
        return new Column(
                Identifier.of(name),
                DataType.varchar("VARCHAR2", 30),
                true,
                new DefaultValue(null),
                new Description(description),
                false,
                1);
    }
}
