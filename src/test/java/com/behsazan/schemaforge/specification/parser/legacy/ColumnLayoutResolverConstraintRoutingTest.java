package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnLayoutResolverConstraintRoutingTest {

    @Test
    void keepsUniqueAndCombinedPrimaryForeignTokensInKeyChannel() {
        ColumnLayoutResolver.Layout layout = new ColumnLayoutResolver.Layout(
                ColumnLayoutResolver.Kind.STANDARD_10);

        ColumnLayoutResolver.ResolvedColumn column = layout.resolve(List.of(
                "عنوان",
                "CUSTOMER_CODE",
                "C",
                "20",
                "UK1 UQ2 PFK3",
                "IX1 UIX2",
                "Y",
                "VARCHAR",
                "20",
                ""
        ));

        assertEquals("UK1 UQ2 PFK3", column.key());
        assertEquals("IX1 UIX2", column.index());
    }

    @Test
    void keepsPlainPrimaryAndForeignTokensInKeyChannel() {
        ColumnLayoutResolver.Layout layout = new ColumnLayoutResolver.Layout(
                ColumnLayoutResolver.Kind.STANDARD_10);

        ColumnLayoutResolver.ResolvedColumn column = layout.resolve(List.of(
                "عنوان",
                "ID",
                "N",
                "9",
                "PK1 FK1",
                "IX1",
                "Y",
                "DECIMAL",
                "9",
                ""
        ));

        assertEquals("PK1 FK1", column.key());
        assertEquals("IX1", column.index());
    }
}
