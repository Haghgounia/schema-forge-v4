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
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void recognizesShortenedPhysicalObjectNameAsSameDesiredIndex() {
        String logicalName = "IX_PRODUCT_WITH_A_VERY_LONG_BUSINESS_NAME_AND_A_VERY_LONG_COLUMN_NAME_FOR_LOOKUP";
        Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(
                DatabasePlatform.POSTGRESQL, Identifier.of(logicalName));
        Column id = Column.required("ID", DataType.numeric("NUMERIC", 19, 0));
        Index liveIndex = new Index(physical,
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Index desiredIndex = new Index(Identifier.of(logicalName),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Table live = Table.builder("APP", "PRODUCT").addColumn(id).addIndex(liveIndex).build();
        Table desired = Table.builder("APP", "PRODUCT").addColumn(id).addIndex(desiredIndex).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.POSTGRESQL, live, desired);

        assertTrue(plan.objectChanges().isEmpty());
    }
}
