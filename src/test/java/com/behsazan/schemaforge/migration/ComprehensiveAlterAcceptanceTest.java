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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R7.5A offline acceptance gate for the complete ALTER/Migration M2 change matrix across registered platforms. */
class ComprehensiveAlterAcceptanceTest {

    @Test
    void detectsEveryColumnChangeKindForAllRegisteredPlatforms() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, liveColumns(), desiredColumns());
            Set<ColumnChangeKind> kinds = plan.columnChanges().stream()
                    .map(ColumnChange::kind)
                    .collect(Collectors.toSet());

            assertEquals(EnumSet.allOf(ColumnChangeKind.class), kinds, platform + " column matrix");
            assertTrue(plan.columnChanges().stream().anyMatch(change ->
                    change.kind() == ColumnChangeKind.DROP_COLUMN && change.risk() == MigrationRisk.DESTRUCTIVE),
                    platform + " DROP COLUMN must remain destructive");
            assertTrue(plan.columnChanges().stream().anyMatch(change ->
                    change.kind() == ColumnChangeKind.ALTER_NULLABILITY && change.risk() == MigrationRisk.REVIEW),
                    platform + " NOT NULL transition must require review");
        }
    }

    @Test
    void detectsAddDropAndReplaceForEveryOwnedStructuralObjectForAllRegisteredPlatforms() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertStructuralKinds(platform, emptyStructure(), fullStructure("A"), TableObjectChangeKind.ADD);
            assertStructuralKinds(platform, fullStructure("A"), emptyStructure(), TableObjectChangeKind.DROP);
            assertStructuralKinds(platform, fullStructure("A"), fullStructure("B"), TableObjectChangeKind.REPLACE);
        }
    }

    @Test
    void rendersFullColumnMatrixWithoutAbortingAndBlocksDestructiveSqlByDefault() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, liveColumns(), desiredColumns());
            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

            assertFalse(sql.isBlank(), platform + " renderer returned empty SQL");
            assertTrue(sql.contains("DROP COLUMN"), platform + " missing DROP COLUMN review SQL");
            assertTrue(sql.contains("-- "), platform + " destructive/review output must contain commented safety SQL");
        }
    }

    @Test
    void identicalDesiredAndLiveTablesProduceZeroResidualDiffForAllRegisteredPlatforms() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table table = fullStructure("A");
            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, table, table);
            assertTrue(plan.empty(), platform + " second comparison must have zero residual changes");
        }
    }

    private static void assertStructuralKinds(
            DatabasePlatform platform, Table live, Table desired, TableObjectChangeKind expectedKind) {
        TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);
        Set<TableObjectType> types = plan.objectChanges().stream()
                .filter(change -> change.kind() == expectedKind)
                .map(TableObjectChange::objectType)
                .collect(Collectors.toSet());
        assertEquals(EnumSet.allOf(TableObjectType.class), types,
                platform + " structural " + expectedKind + " matrix");
    }

    private static Table liveColumns() {
        return Table.builder("APP", "R75_MATRIX")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, false, null, 1))
                .addColumn(column("NAME", DataType.varchar("VARCHAR2", 50), true, null, false, null, 2))
                .addColumn(column("DEFAULTED", DataType.varchar("VARCHAR2", 20), true, "'OLD'", false, null, 3))
                .addColumn(column("IDENT_COL", DataType.numeric("NUMBER", 10, 0), false, null, false, null, 4))
                .addColumn(column("GENERATED_COL", DataType.numeric("NUMBER", 10, 0), true, null, false, "ID + 1", 5))
                .addColumn(column("LEGACY_COL", DataType.varchar("VARCHAR2", 20), true, null, false, null, 6))
                .build();
    }

    private static Table desiredColumns() {
        return Table.builder("APP", "R75_MATRIX")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, false, null, 1))
                .addColumn(column("NAME", DataType.varchar("VARCHAR2", 100), false, null, false, null, 2))
                .addColumn(column("DEFAULTED", DataType.varchar("VARCHAR2", 20), true, "'NEW'", false, null, 3))
                .addColumn(column("IDENT_COL", DataType.numeric("NUMBER", 10, 0), false, null, true, null, 4))
                .addColumn(column("GENERATED_COL", DataType.numeric("NUMBER", 10, 0), true, null, false, "ID + 2", 5))
                .addColumn(column("NEW_COL", DataType.varchar("VARCHAR2", 20), true, null, false, null, 6))
                .build();
    }

    private static Column column(
            String name, DataType type, boolean nullable, String defaultExpression,
            boolean identity, String generatedExpression, int ordinal) {
        return new Column(
                Identifier.of(name), type, nullable, new DefaultValue(defaultExpression), Description.empty(),
                identity, ordinal, generatedExpression);
    }

    private static Table emptyStructure() {
        return baseStructureBuilder().build();
    }

    private static Table fullStructure(String variant) {
        boolean b = "B".equals(variant);
        return baseStructureBuilder()
                .primaryKey(new PrimaryKey(Identifier.of("PK_R75_MATRIX"),
                        b ? List.of(Identifier.of("ID"), Identifier.of("CODE")) : List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_R75_MATRIX_CODE"),
                        List.of(Identifier.of("CODE")), b, b))
                .addCheck(new CheckConstraint(Identifier.of("CHK_R75_MATRIX_STATUS"),
                        b ? "STATUS IN ('A','I','S')" : "STATUS IN ('A','I')"))
                .addIndex(new Index(Identifier.of("IX_R75_MATRIX_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), b ? SortDirection.DESC : SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_R75_MATRIX_PARENT_ID"),
                        List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "R75_MATRIX"),
                        List.of(Identifier.of("ID")),
                        b ? ReferentialAction.CASCADE : ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private static Table.Builder baseStructureBuilder() {
        return Table.builder("APP", "R75_MATRIX")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1)));
    }
}
