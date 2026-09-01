package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.naming.LogicalObjectNamingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationConvergenceRegressionTest {

    @Test
    void oracleRoundTripTreatsSequenceIdentityTimestampAndRedundantIndexAsEquivalent() {
        Column liveId = new Column(Identifier.of("PRODUCT_ID"), DataType.numeric("NUMBER", 19, 0), false,
                new DefaultValue("APP.SEQ_PRODUCT.NEXTVAL"), Description.empty(), false, 1);
        Column desiredId = new Column(Identifier.of("PRODUCT_ID"), DataType.numeric("NUMBER", 19, 0), false,
                new DefaultValue(null), Description.empty(), true, 1);
        Column liveTimestamp = new Column(Identifier.of("CREATED_AT"),
                DataType.numeric("TIMESTAMP", 6, null), false,
                new DefaultValue("SYSTIMESTAMP"), Description.empty(), false, 2);
        Column desiredTimestamp = new Column(Identifier.of("CREATED_AT"),
                DataType.simple("TIMESTAMP"), false,
                new DefaultValue("SYSTIMESTAMP"), Description.empty(), false, 2);

        Table live = Table.builder("APP", "PRODUCT")
                .addColumn(liveId).addColumn(liveTimestamp)
                .primaryKey(new PrimaryKey(Identifier.of("PK_PRODUCT"), List.of(Identifier.of("PRODUCT_ID"))))
                .build();
        Table desired = Table.builder("APP", "PRODUCT")
                .addColumn(desiredId).addColumn(desiredTimestamp)
                .primaryKey(new PrimaryKey(Identifier.of("PK_PRODUCT"), List.of(Identifier.of("PRODUCT_ID"))))
                .addIndex(new Index(Identifier.of("IX_PRODUCT_PRODUCT_ID"),
                        List.of(new IndexColumn(Identifier.of("PRODUCT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);

        assertTrue(plan.empty(), () -> "same-model Oracle round-trip must be a no-op: " + plan);
    }

    @Test
    void ignoresInvalidDesiredCheckInsteadOfGeneratingAnAlterForUnknownColumns() {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 19, 0));
        Table live = Table.builder("APP", "RULES").addColumn(id).build();
        Table desired = Table.builder("APP", "RULES")
                .addColumn(id)
                .addCheck(new CheckConstraint(Identifier.of("CHK_RULES_RANGE"), "MIN_COUNT <= MAX_COUNT"))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);

        assertTrue(plan.objectChanges().isEmpty());
    }

    @Test
    void recognizesShortenedPhysicalObjectNameAsSameDesiredFormulaIndex() {
        String tableName = "PRODUCT_WITH_A_VERY_LONG_BUSINESS_NAME_AND_LOOKUP_SUFFIX";
        String columnName = "REFERENCE_COLUMN_WITH_AN_EXTREMELY_LONG_NAME";
        Column column = Column.required(columnName, DataType.numeric("NUMERIC", 19, 0));
        Index desiredIndex = new Index(Identifier.of("SOURCE_INDEX_NAME_MUST_BE_IGNORED"),
                List.of(new IndexColumn(Identifier.of(columnName), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Table desired = Table.builder("APP", tableName).addColumn(column).addIndex(desiredIndex).build();
        Identifier logical = LogicalObjectNamingPolicy.index(desired, desiredIndex);
        Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(DatabasePlatform.POSTGRESQL, logical);
        Index liveIndex = new Index(physical, desiredIndex.columns(), IndexType.NORMAL, Description.empty());
        Table live = Table.builder("APP", tableName).addColumn(column).addIndex(liveIndex).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.POSTGRESQL, live, desired);

        assertTrue(plan.objectChanges().isEmpty());
    }
    @Test
    void oracleTreatsExplicitNullDefaultAsNoDefault() {
        Column live = new Column(Identifier.of("ELIGIBILITY_RULE_ID"),
                DataType.numeric("NUMBER", 19, 0), false, new DefaultValue("NULL"),
                Description.empty(), false, 1);
        Column desired = new Column(Identifier.of("ELIGIBILITY_RULE_ID"),
                DataType.numeric("NUMBER", 19, 0), false, new DefaultValue(null),
                Description.empty(), false, 1);

        Table liveTable = Table.builder("PDL", "LOAN_ELIGIBILITY_EXTENSION")
                .addColumn(live).build();
        Table desiredTable = Table.builder("PDL", "LOAN_ELIGIBILITY_EXTENSION")
                .addColumn(desired).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(
                DatabasePlatform.ORACLE, liveTable, desiredTable);

        assertTrue(plan.columnChanges().isEmpty(), () -> "NULL default must equal no default: " + plan);
    }

    @Test
    void oracleRenamesLiveNonstandardIndexToTheFormulaName() {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 19, 0));
        Index liveIndex = new Index(Identifier.of("IX_PATTERN_OPERATION_51"),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Index desiredIndex = new Index(Identifier.of("IX_PATTERN_OPERATION__51"),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Table live = Table.builder("PDL", "PATTERN_OPERATION_DETAIL")
                .addColumn(id).addIndex(liveIndex).build();
        Table desired = Table.builder("PDL", "PATTERN_OPERATION_DETAIL")
                .addColumn(id).addIndex(desiredIndex).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);
        assertEquals(1, plan.objectChanges().size());
        assertEquals(TableObjectChangeKind.RENAME, plan.objectChanges().getFirst().kind());

        String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
        assertTrue(sql.contains("ALTER INDEX PDL.IX_PATTERN_OPERATION_51 RENAME TO IX_PATTERN_OPERATION_DETAIL_ID;"), sql);
        assertTrue(!sql.contains("IX_PATTERN_OPERATION__51"), sql);
        assertTrue(!sql.contains("DROP INDEX"), sql);
        assertTrue(!sql.contains("CREATE INDEX"), sql);
    }

}
