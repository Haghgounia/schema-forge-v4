package com.behsazan.schemaforge.naming;

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
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalObjectNamingPolicyTest {

    @Test
    void derivesEveryLogicalObjectNameFromStructureAndIgnoresInputNames() {
        PrimaryKey pk = new PrimaryKey(Identifier.of("SOURCE_PK_NAME"), List.of(Identifier.of("ORDER_ITEM_ID")));
        UniqueKey uk = new UniqueKey(Identifier.of("SOURCE_UK_NAME"),
                List.of(Identifier.of("ORDER_ID"), Identifier.of("LINE_NO")));
        ForeignKey fk = new ForeignKey(Identifier.of("EA_FK_99"),
                List.of(Identifier.of("ORDER_ID")), QualifiedName.of("APP", "ORDERS"),
                List.of(Identifier.of("ORDER_ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION);
        CheckConstraint check = new CheckConstraint(Identifier.of("CUSTOM_CHECK"), "STATUS IN (0, 1)");
        Index normal = new Index(Identifier.of("SOURCE_IX"),
                List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Index unique = new Index(Identifier.of("SOURCE_UIX"),
                List.of(new IndexColumn(Identifier.of("EXTERNAL_CODE"), SortDirection.ASC)),
                IndexType.UNIQUE, Description.empty());

        Table table = Table.builder("APP", "ORDER_ITEM")
                .addColumn(Column.required("ORDER_ITEM_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("ORDER_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("LINE_NO", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.required("STATUS", DataType.numeric("NUMBER", 1, 0)))
                .addColumn(Column.required("EXTERNAL_CODE", DataType.varchar("VARCHAR2", 50)))
                .primaryKey(pk)
                .addUniqueKey(uk)
                .addForeignKey(fk)
                .addCheck(check)
                .addIndex(normal)
                .addIndex(unique)
                .build();

        assertEquals("PK_ORDER_ITEM", LogicalObjectNamingPolicy.primaryKey(table, pk).value());
        assertEquals("PK_ORDER_ITEM_ORDER_ITEM_ID", LogicalObjectNamingPolicy.primaryKeyIndex(table, pk).value());
        assertEquals("UK_ORDER_ITEM_ORDER_ID_LINE_NO", LogicalObjectNamingPolicy.uniqueKey(table, uk).value());
        assertEquals("UK_ORDER_ITEM_ORDER_ID_LINE_NO", LogicalObjectNamingPolicy.uniqueKeyIndex(table, uk).value());
        assertEquals("FK_ORDER_ITEM_ORDER_ID", LogicalObjectNamingPolicy.foreignKey(table, fk).value());
        assertEquals("CHK_ORDER_ITEM_STATUS", LogicalObjectNamingPolicy.checkConstraint(table, check).value());
        assertEquals("IX_ORDER_ITEM_STATUS", LogicalObjectNamingPolicy.index(table, normal).value());
        assertEquals("IX_ORDER_ITEM_EXTERNAL_CODE", LogicalObjectNamingPolicy.index(table, unique).value());

        assertNotEquals(pk.name(), LogicalObjectNamingPolicy.primaryKey(table, pk), "input PK name must be ignored");
        assertNotEquals(uk.name(), LogicalObjectNamingPolicy.uniqueKey(table, uk), "input UK name must be ignored");
        assertNotEquals(fk.name(), LogicalObjectNamingPolicy.foreignKey(table, fk), "input FK name must be ignored");
        assertNotEquals(check.name(), LogicalObjectNamingPolicy.checkConstraint(table, check), "input CHECK name must be ignored");
        assertNotEquals(normal.name(), LogicalObjectNamingPolicy.index(table, normal), "input index name must be ignored");
        assertNotEquals(unique.name(), LogicalObjectNamingPolicy.index(table, unique), "input unique-index name must be ignored");
    }

    @Test
    void disambiguatesSameBaseNamesFromStructureWithoutUsingSourceNames() {
        ForeignKey firstFk = new ForeignKey(Identifier.of("SOURCE_FK_A"),
                List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "PARENT_A"),
                List.of(Identifier.of("ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION);
        ForeignKey secondFk = new ForeignKey(Identifier.of("SOURCE_FK_B"),
                List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "PARENT_B"),
                List.of(Identifier.of("ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION);
        Index normal = new Index(Identifier.of("SOURCE_INDEX_A"),
                List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Index unique = new Index(Identifier.of("SOURCE_INDEX_B"),
                List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                IndexType.UNIQUE, Description.empty());
        CheckConstraint firstCheck = new CheckConstraint(Identifier.of("SOURCE_CHECK_A"), "STATUS IN (0, 1)");
        CheckConstraint secondCheck = new CheckConstraint(Identifier.of("SOURCE_CHECK_B"), "STATUS IN (0, 1, 2)");

        Table table = Table.builder("APP", "CHILD_TABLE")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.required("STATUS", DataType.numeric("NUMBER", 1, 0)))
                .addForeignKey(firstFk)
                .addForeignKey(secondFk)
                .addIndex(normal)
                .addIndex(unique)
                .addCheck(firstCheck)
                .addCheck(secondCheck)
                .build();

        String fkA = LogicalObjectNamingPolicy.foreignKey(table, firstFk).value();
        String fkB = LogicalObjectNamingPolicy.foreignKey(table, secondFk).value();
        String ixA = LogicalObjectNamingPolicy.index(table, normal).value();
        String ixB = LogicalObjectNamingPolicy.index(table, unique).value();
        String chkA = LogicalObjectNamingPolicy.checkConstraint(table, firstCheck).value();
        String chkB = LogicalObjectNamingPolicy.checkConstraint(table, secondCheck).value();

        assertTrue(fkA.matches("FK_CHILD_TABLE_PARENT_ID_[0-9A-F]{8}"), fkA);
        assertTrue(fkB.matches("FK_CHILD_TABLE_PARENT_ID_[0-9A-F]{8}"), fkB);
        assertNotEquals(fkA, fkB, "same child columns pointing at different parents must not collide");
        assertTrue(ixA.matches("IX_CHILD_TABLE_CODE_[0-9A-F]{8}"), ixA);
        assertTrue(ixB.matches("IX_CHILD_TABLE_CODE_[0-9A-F]{8}"), ixB);
        assertNotEquals(ixA, ixB, "normal and unique standalone indexes must not collide");
        assertTrue(chkA.matches("CHK_CHILD_TABLE_STATUS_[0-9A-F]{8}"), chkA);
        assertTrue(chkB.matches("CHK_CHILD_TABLE_STATUS_[0-9A-F]{8}"), chkB);
        assertNotEquals(chkA, chkB, "multiple checks on the same column must not collide");
    }

}
