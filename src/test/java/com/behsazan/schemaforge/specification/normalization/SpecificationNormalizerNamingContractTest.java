package com.behsazan.schemaforge.specification.normalization;

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
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecificationNormalizerNamingContractTest {

    @Test
    void overwritesAllInputObjectNamesWithTheCrossServiceFormula() {
        Table source = Table.builder("APP", "ORDER_ITEM")
                .addColumn(Column.required("ORDER_ITEM_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("ORDER_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("LINE_NO", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.required("STATUS", DataType.numeric("NUMBER", 1, 0)))
                .primaryKey(new PrimaryKey(Identifier.of("EA_PK_17"), List.of(Identifier.of("ORDER_ITEM_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("INPUT_UNIQUE"),
                        List.of(Identifier.of("ORDER_ID"), Identifier.of("LINE_NO"))))
                .addForeignKey(new ForeignKey(Identifier.of("FK_FROM_XMI"),
                        List.of(Identifier.of("ORDER_ID")), QualifiedName.of("APP", "ORDERS"),
                        List.of(Identifier.of("ORDER_ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .addCheck(new CheckConstraint(Identifier.of("CK_SOURCE_STATUS"), "STATUS IN (0, 1)"))
                .addIndex(new Index(Identifier.of("IDX_SOURCE_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        DatabaseSchema normalized = new SpecificationNormalizer().normalize(
                DatabaseSchema.builder("APP").addTable(source).build());
        Table table = normalized.tables().getFirst();

        assertEquals("PK_ORDER_ITEM", table.primaryKey().orElseThrow().name().value());
        assertEquals("UK_ORDER_ITEM_ORDER_ID_LINE_NO", table.uniqueKeys().getFirst().name().value());
        assertEquals("FK_ORDER_ITEM_ORDER_ID", table.foreignKeys().getFirst().name().value());
        assertEquals("CHK_ORDER_ITEM_STATUS", table.checkConstraints().getFirst().name().value());
        assertEquals("IX_ORDER_ITEM_STATUS", table.indexes().getFirst().name().value());
    }
}
