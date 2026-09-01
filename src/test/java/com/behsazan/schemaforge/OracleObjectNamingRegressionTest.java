package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleObjectNamingRegressionTest {

    @Test
    void ignoresSourceIndexNamesAndUsesOneStructuralFormula() {
        Table operation = Table.builder("PDL", "PATTERN_OPERATION")
                .addColumn(Column.required("OPERATION_ID", DataType.numeric("NUMBER", 19, 0)))
                .addIndex(index("IX_PATTERN_OPERATION_2", "OPERATION_ID"))
                .build();
        Table detail = Table.builder("PDL", "PATTERN_OPERATION_DETAIL")
                .addColumn(Column.required("SUB_OPERATION_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("DETAIL_ID", DataType.numeric("NUMBER", 19, 0)))
                .addIndex(index("IX_PATTERN_OPERATION__2", "SUB_OPERATION_ID"))
                .addIndex(index("IX_PATTERN_OPERATION__51", "DETAIL_ID"))
                .build();
        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("PDL").addTable(operation).addTable(detail).build());

        assertTrue(sql.contains("CREATE INDEX PDL.IX_PATTERN_OPERATION_OPERATION_ID ON PDL.PATTERN_OPERATION(OPERATION_ID)"));
        assertTrue(sql.contains("CREATE INDEX PDL.IX_PATTERN_OPERATION_DETAIL_SUB_OPERATION_ID ON PDL.PATTERN_OPERATION_DETAIL(SUB_OPERATION_ID)"));
        assertTrue(sql.contains("CREATE INDEX PDL.IX_PATTERN_OPERATION_DETAIL_DETAIL_ID ON PDL.PATTERN_OPERATION_DETAIL(DETAIL_ID)"));
        assertFalse(sql.contains("IX_PATTERN_OPERATION__2"));
        assertFalse(sql.contains("IX_PATTERN_OPERATION__51"));
        assertFalse(sql.contains("[ERROR][PHYSICAL_NAME_COLLISION]"));
    }

    @Test
    void blocksCheckThatReferencesColumnsMissingFromItsTable() {
        Table table = Table.builder("PDL", "LOAN_PRODUCT_COLLATERAL_RULE")
                .addColumn(Column.required("LOAN_COLLATERAL_RULE_ID", DataType.numeric("NUMBER", 19, 0)))
                .addCheck(new CheckConstraint(Identifier.of("CHK_PCR_COUNT_RANGE"),
                        "MIN_COUNT IS NULL OR MAX_COUNT IS NULL OR MIN_COUNT <= MAX_COUNT"))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("PDL").addTable(table).build());

        assertTrue(sql.contains("CHECK-COL-001"));
        assertFalse(sql.contains("CHK_PCR_COUNT_RANGE"));
        assertFalse(sql.contains("ADD CONSTRAINT"));
    }

    @Test
    void usesSampleConventionForOraclePrimaryKeyBackingIndexWithoutDuplicatingColumnSuffix() {
        Table plain = Table.builder("PDL", "PRODUCT")
                .addColumn(Column.required("PRODUCT_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_PRODUCT"), List.of(Identifier.of("PRODUCT_ID"))))
                .build();
        Table alreadySuffixed = Table.builder("PDL", "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS_CUSTOMER_ID"),
                        List.of(Identifier.of("CUSTOMER_ID"))))
                .build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                DatabaseSchema.builder("PDL").addTable(plain).addTable(alreadySuffixed).build());

        assertTrue(sql.contains("CONSTRAINT PK_PRODUCT PRIMARY KEY"));
        assertTrue(sql.contains("CONSTRAINT PK_CUSTOMERS PRIMARY KEY"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX PDL.PK_PRODUCT_PRODUCT_ID ON PDL.PRODUCT(PRODUCT_ID)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX PDL.PK_CUSTOMERS_CUSTOMER_ID ON PDL.CUSTOMERS(CUSTOMER_ID)"));
        assertFalse(sql.contains("CONSTRAINT PK_CUSTOMERS_CUSTOMER_ID PRIMARY KEY"));
        assertFalse(sql.contains("PK_CUSTOMERS_CUSTOMER_ID_CUSTOMER_ID"));
    }

    private static Index index(String name, String column) {
        return new Index(Identifier.of(name),
                List.of(new IndexColumn(Identifier.of(column), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
    }
}
