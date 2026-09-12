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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M7 offline gate for MariaDB ALTER/Migration rendering. */
class MariaDbMigrationSqlRendererTest {

    @Test
    void rendersOneCombinedModifyForTypeNullabilityAndDefaultDrift() {
        Table live = Table.builder("APP", "ACCOUNTS")
                .addColumn(column("STATUS", DataType.varchar("VARCHAR", 10), true, "'OLD'", 1))
                .build();
        Table desired = Table.builder("APP", "ACCOUNTS")
                .addColumn(column("STATUS", DataType.varchar("VARCHAR", 30), false, "'NEW'", 1))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MARIADB, live, desired);
        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

        assertEquals(1, occurrences(sql, "MODIFY COLUMN `STATUS`"));
        assertTrue(sql.contains("`STATUS` VARCHAR(30) DEFAULT 'NEW' NOT NULL"));
        assertTrue(sql.contains("change is covered by the combined column-definition statement above"));
    }

    @Test
    void rendersMariaDbSpecificDestructiveDropSyntaxWhenExplicitlyConfirmed() {
        Table live = structuredTable();
        Table desired = baseTable();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MARIADB, live, desired);
        String sql = new MigrationSqlRenderer().render(plan, new MigrationRenderOptions(true));

        assertTrue(sql.contains("ALTER TABLE `APP`.`CHILDREN` DROP PRIMARY KEY;"));
        assertTrue(sql.contains("ALTER TABLE `APP`.`CHILDREN` DROP FOREIGN KEY `FK_CHILDREN_PARENT`;"));
        assertTrue(sql.contains("ALTER TABLE `APP`.`CHILDREN` DROP INDEX `UK_CHILDREN_CODE`;"));
        assertTrue(sql.contains("ALTER TABLE `APP`.`CHILDREN` DROP CONSTRAINT `CHK_CHILDREN_STATUS`;"));
        assertTrue(sql.contains("ALTER TABLE `APP`.`CHILDREN` DROP INDEX `IX_CHILDREN_STATUS`;"));
        assertFalse(sql.contains("DROP CHECK `CHK_CHILDREN_STATUS`"));
    }

    @Test
    void safeDefaultsKeepMariaDbDestructiveDropsCommented() {
        TableMigrationPlan plan = new SchemaDiffEngine().diff(
                DatabasePlatform.MARIADB, structuredTable(), baseTable());

        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

        assertTrue(sql.contains("-- ALTER TABLE `APP`.`CHILDREN` DROP PRIMARY KEY;"));
        assertTrue(sql.contains("-- ALTER TABLE `APP`.`CHILDREN` DROP FOREIGN KEY `FK_CHILDREN_PARENT`;"));
        assertTrue(sql.contains("-- BLOCKED: destructive structural SQL is commented out"));
    }

    @Test
    void rendersMariaDbStructuralAddsThroughMariaDbDialect() {
        TableMigrationPlan plan = new SchemaDiffEngine().diff(
                DatabasePlatform.MARIADB, baseTable(), structuredTable());

        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

        assertTrue(sql.contains("ADD CONSTRAINT `PK_CHILDREN` PRIMARY KEY"));
        assertTrue(sql.contains("ADD CONSTRAINT `UK_CHILDREN_CODE` UNIQUE"));
        assertTrue(sql.contains("ADD CONSTRAINT `CHK_CHILDREN_STATUS` CHECK"));
        assertTrue(sql.contains("CREATE INDEX `IX_CHILDREN_STATUS` ON `APP`.`CHILDREN`"));
        // ADD uses the frozen cross-service logical naming contract, not the source-provided FK name.
        assertTrue(sql.contains("ADD CONSTRAINT `FK_CHILDREN_PARENT_ID` FOREIGN KEY"));
        assertFalse(sql.contains("ADD CONSTRAINT `FK_CHILDREN_PARENT` FOREIGN KEY"));
    }

    private static Table baseTable() {
        return Table.builder("APP", "CHILDREN")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .build();
    }

    private static Table structuredTable() {
        return Table.builder("APP", "CHILDREN")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CHILDREN"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_CHILDREN_CODE"), List.of(Identifier.of("CODE")), false, false))
                .addCheck(new CheckConstraint(
                        Identifier.of("CHK_CHILDREN_STATUS"), "STATUS IN ('A','I')"))
                .addIndex(new Index(
                        Identifier.of("IX_CHILDREN_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL,
                        Description.empty()))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_CHILDREN_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of("APP", "PARENTS"),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultExpression, int ordinal) {
        return new Column(
                Identifier.of(name), type, nullable, new DefaultValue(defaultExpression), Description.empty(),
                false, ordinal, null);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }
}
