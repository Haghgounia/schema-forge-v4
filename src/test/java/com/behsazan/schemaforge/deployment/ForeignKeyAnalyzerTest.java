package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForeignKeyAnalyzerTest {
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();

    @Test
    void resolvesPhysicalForeignKeyInsideOwnerSchema() {
        Table customer = tableWithPrimaryKey("TSTSHMA", "CUSTOMER", "ID");
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(customer, account));

        assertTrue(result.deployable());
        assertEquals(1, result.physicalForeignKeys());
        assertEquals(1, result.resolvedPhysicalForeignKeys());
        assertEquals(0, result.errorCount());
    }

    @Test
    void missingParentTableIsBlocker() {
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(account));

        assertFalse(result.deployable());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code() == ForeignKeyAnalysisCode.MISSING_REFERENCED_TABLE));
    }

    @Test
    void missingReferencedColumnIsBlocker() {
        Table customer = tableWithPrimaryKey("TSTSHMA", "CUSTOMER", "ID");
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of("TSTSHMA", "CUSTOMER"), List.of(id("LEGACY_ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(customer, account));

        assertFalse(result.deployable());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code() == ForeignKeyAnalysisCode.MISSING_REFERENCED_COLUMN));
    }

    @Test
    void nonUniqueReferencedColumnsAreBlocker() {
        Table customer = Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(column("ID"))
                .addColumn(column("LEGACY_ID"))
                .primaryKey(new PrimaryKey(id("PK_CUSTOMER"), List.of(id("ID"))))
                .build();
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of("TSTSHMA", "CUSTOMER"), List.of(id("LEGACY_ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(customer, account));

        assertFalse(result.deployable());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code() == ForeignKeyAnalysisCode.REFERENCED_COLUMNS_NOT_UNIQUE));
    }

    @Test
    void logicalForeignKeyIsReportedButNotBlocker() {
        Table external = Table.builder("TSTSHMA", "LOCAL")
                .addColumn(column("ID"))
                .addColumn(column("REMOTE_ID"))
                .primaryKey(new PrimaryKey(id("PK_LOCAL"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_LOCAL_REMOTE"), List.of(id("REMOTE_ID")),
                        QualifiedName.of("REMOTE", "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION,
                        false, false, false, true))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(external));

        assertTrue(result.deployable());
        assertEquals(1, result.logicalForeignKeys());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code() == ForeignKeyAnalysisCode.LOGICAL_FOREIGN_KEY_SKIPPED));
    }

    @Test
    void dependencyCycleIsWarningNotBlocker() {
        Table a = Table.builder("TSTSHMA", "A")
                .addColumn(column("ID"))
                .addColumn(column("B_ID"))
                .primaryKey(new PrimaryKey(id("PK_A"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_A_B"), List.of(id("B_ID")),
                        QualifiedName.of(null, "B"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
        Table b = Table.builder("TSTSHMA", "B")
                .addColumn(column("ID"))
                .addColumn(column("A_ID"))
                .primaryKey(new PrimaryKey(id("PK_B"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_B_A"), List.of(id("A_ID")),
                        QualifiedName.of(null, "A"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        ForeignKeyAnalysisResult result = analyzer.analyze(schema(a, b));

        assertTrue(result.deployable());
        assertEquals(1, result.cycleGroups());
        assertEquals(1, result.warningCount());
    }

    @Test
    void integratedAssemblerRejectsMultipleVersionsOfSameTable() {
        DatabaseSchema first = schema(tableWithPrimaryKey("TSTSHMA", "CUSTOMER", "ID"));
        DatabaseSchema second = schema(tableWithPrimaryKey("TSTSHMA", "CUSTOMER", "ID"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new IntegratedSchemaAssembler().assemble("INTEGRATED", List.of(first, second)));

        assertTrue(error.getMessage().startsWith("INPUT_DUPLICATE_TABLE:"));
    }

    private static DatabaseSchema schema(Table... tables) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("TEST");
        for (Table table : tables) builder.addTable(table);
        return builder.build();
    }

    private static Table tableWithPrimaryKey(String schema, String table, String primaryKeyColumn) {
        return Table.builder(schema, table)
                .addColumn(column(primaryKeyColumn))
                .primaryKey(new PrimaryKey(id("PK_" + table), List.of(id(primaryKeyColumn))))
                .build();
    }

    private static Column column(String name) {
        return Column.required(name, DataType.numeric("NUMBER", 19, 0));
    }

    private static Identifier id(String value) {
        return Identifier.of(value);
    }
}
