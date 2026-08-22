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

class SchemaDiffEngineTest {

    @Test
    void detectsAddDropTypeNullabilityAndDefaultWithoutInferringRename() {
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("NAME", DataType.varchar("VARCHAR2", 50), true, null, 2))
                .addColumn(column("LEGACY_CODE", DataType.varchar("VARCHAR2", 20), true, null, 3))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(column("ID", DataType.numeric("NUMBER", 10, 0), false, null, 1))
                .addColumn(column("NAME", DataType.varchar("VARCHAR2", 100), false, "'UNKNOWN'", 2))
                .addColumn(column("MOBILE_NO", DataType.varchar("VARCHAR2", 20), true, null, 3))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);

        assertEquals(5, plan.columnChanges().size());
        assertTrue(plan.columnChanges().stream().anyMatch(change ->
                change.kind() == ColumnChangeKind.ALTER_TYPE && change.risk() == MigrationRisk.SAFE));
        assertTrue(plan.columnChanges().stream().anyMatch(change ->
                change.kind() == ColumnChangeKind.ALTER_NULLABILITY && change.risk() == MigrationRisk.REVIEW));
        assertTrue(plan.columnChanges().stream().anyMatch(change ->
                change.kind() == ColumnChangeKind.ALTER_DEFAULT && change.risk() == MigrationRisk.REVIEW));
        assertTrue(plan.columnChanges().stream().anyMatch(change ->
                change.kind() == ColumnChangeKind.ADD_COLUMN && change.columnName().normalized().equals("MOBILE_NO")));
        assertTrue(plan.columnChanges().stream().anyMatch(change ->
                change.kind() == ColumnChangeKind.DROP_COLUMN
                        && change.risk() == MigrationRisk.DESTRUCTIVE
                        && change.rationale().contains("rename is never inferred")));
    }


    @Test
    void treatsMySqlIdentityNextvalAsEffectiveAutoIncrementDefault() {
        Column liveId = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0), false,
                new DefaultValue(null), Description.empty(), true, 1);
        Column desiredId = new Column(
                Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0), false,
                new DefaultValue("APP.SEQ_CUSTOMER.NEXTVAL"), Description.empty(), true, 1);

        Table live = Table.builder("APP", "CUSTOMER").addColumn(liveId).build();
        Table desired = Table.builder("APP", "CUSTOMER").addColumn(desiredId).build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);

        assertTrue(plan.empty());
    }

    @Test
    void detectsPkFkUkCheckAndIndexChangesWithoutTreatingPhysicalOptionsAsLogicalDrift() {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 10, 0));
        Column parentId = Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 10, 0));
        Column code = Column.nullable("CODE", DataType.varchar("VARCHAR2", 30));
        Column status = Column.nullable("STATUS", DataType.varchar("VARCHAR2", 1));

        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(id).addColumn(parentId).addColumn(code).addColumn(status)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_CODE"), List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMER_STATUS"), "STATUS IN ('A','I')"))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_STATUS"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMER_PARENT"),
                        List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "CUSTOMER"),
                        List.of(Identifier.of("ID")), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(id).addColumn(parentId).addColumn(code).addColumn(status)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_CODE"),
                        List.of(Identifier.of("CODE"), Identifier.of("STATUS"))))
                .addCheck(new CheckConstraint(Identifier.of("CK_CUSTOMER_STATUS"), "STATUS IN ('A','I','S')"))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(Identifier.of("FK_CUSTOMER_PARENT"),
                        List.of(Identifier.of("PARENT_ID")), QualifiedName.of("APP", "CUSTOMER"),
                        List.of(Identifier.of("ID")), ReferentialAction.CASCADE, ReferentialAction.NO_ACTION))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);

        assertEquals(5, plan.objectChanges().size());
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.UNIQUE_KEY && change.kind() == TableObjectChangeKind.REPLACE));
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.CHECK_CONSTRAINT && change.kind() == TableObjectChangeKind.REPLACE));
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.FOREIGN_KEY && change.kind() == TableObjectChangeKind.REPLACE));
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.INDEX && change.kind() == TableObjectChangeKind.ADD));
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.INDEX && change.kind() == TableObjectChangeKind.DROP));
        assertTrue(plan.objectChanges().stream().noneMatch(change -> change.objectType() == TableObjectType.PRIMARY_KEY));
    }

    @Test
    void ignoresMySqlCheckCatalogQuotingAndUtf8CharsetIntroducers() {
        Column status = Column.nullable("STATUS", DataType.varchar("VARCHAR", 1));
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "(`STATUS` in (_utf8mb4'A',_utf8mb4'I',_utf8mb4'S'))"))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "STATUS IN ('A','I','S')"))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);

        assertTrue(plan.objectChanges().stream().noneMatch(change ->
                change.objectType() == TableObjectType.CHECK_CONSTRAINT));
    }

    @Test
    void ignoresMySqlCheckCatalogWhitespaceAroundCommaAndParentheses() {
        Column status = Column.nullable("STATUS", DataType.varchar("VARCHAR", 1));
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "((`STATUS` in (_utf8mb4'A', _utf8mb4'I', _utf8mb4'S')))"))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "STATUS IN ( 'A' , 'I' , 'S' )"))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);

        assertTrue(plan.objectChanges().stream().noneMatch(change ->
                change.objectType() == TableObjectType.CHECK_CONSTRAINT));
    }

    @Test
    void ignoresMySqlCheckCatalogBackslashEscapedQuoteDelimitersFromLiveServer() {
        Column status = Column.nullable("STATUS", DataType.varchar("VARCHAR", 1));
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "(`STATUS` in (_utf8mb4\\'A\\',_utf8mb4\\'I\\',_utf8mb4\\'S\\'))"))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(status)
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_CUSTOMER_STATUS"),
                        "STATUS IN ('A','I','S')"))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);

        assertTrue(plan.objectChanges().stream().noneMatch(change ->
                change.objectType() == TableObjectType.CHECK_CONSTRAINT));
    }

    @Test
    void preservesApostropheSemanticsWhileNormalizingMySqlCatalogEscapedLiterals() {
        String live = SchemaDiffEngine.normalizeCheckExpression(
                DatabasePlatform.MYSQL, "NAME = _utf8mb4\\'O\\'Reilly\\'");
        String desired = SchemaDiffEngine.normalizeCheckExpression(
                DatabasePlatform.MYSQL, "NAME = 'O''Reilly'");
        String different = SchemaDiffEngine.normalizeCheckExpression(
                DatabasePlatform.MYSQL, "NAME = 'OReilly'");

        assertEquals(desired, live);
        assertFalse(live.equals(different));
    }

    @Test
    void preservesCommaAndWhitespaceInsideMySqlCheckStringLiterals() {
        String left = SchemaDiffEngine.normalizeCheckExpression(
                DatabasePlatform.MYSQL, "NOTE IN ('A, B','X Y')");
        String right = SchemaDiffEngine.normalizeCheckExpression(
                DatabasePlatform.MYSQL, "NOTE IN ('A,B','X Y')");

        assertFalse(left.equals(right));
    }

    @Test
    void ignoresMySqlPrimaryKeyCatalogNamePrimaryWhenStructureMatches() {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 10, 0));
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PRIMARY"), List.of(Identifier.of("ID"))))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(id)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), List.of(Identifier.of("ID"))))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, live, desired);

        assertTrue(plan.objectChanges().stream().noneMatch(change -> change.objectType() == TableObjectType.PRIMARY_KEY));
    }

    @Test
    void detectsAddedAndRemovedStructuralObjects() {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 10, 0));
        Column code = Column.nullable("CODE", DataType.varchar("VARCHAR2", 30));
        Table live = Table.builder("APP", "CUSTOMER")
                .addColumn(id).addColumn(code)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMER"), List.of(Identifier.of("ID"))))
                .build();
        Table desired = Table.builder("APP", "CUSTOMER")
                .addColumn(id).addColumn(code)
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMER_CODE"), List.of(Identifier.of("CODE"))))
                .build();

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.POSTGRESQL, live, desired);

        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.PRIMARY_KEY
                        && change.kind() == TableObjectChangeKind.DROP
                        && change.risk() == MigrationRisk.DESTRUCTIVE));
        assertTrue(plan.objectChanges().stream().anyMatch(change ->
                change.objectType() == TableObjectType.UNIQUE_KEY
                        && change.kind() == TableObjectChangeKind.ADD
                        && change.risk() == MigrationRisk.REVIEW));
    }

    @Test
    void classifiesCharacterNarrowingAsDestructive() {
        Table live = table(column("CODE", DataType.varchar("VARCHAR2", 100), true, null, 1));
        Table desired = table(column("CODE", DataType.varchar("VARCHAR2", 20), true, null, 1));

        TableMigrationPlan plan = new SchemaDiffEngine().diff(DatabasePlatform.ORACLE, live, desired);

        assertEquals(List.of(MigrationRisk.DESTRUCTIVE),
                plan.columnChanges().stream().map(ColumnChange::risk).toList());
    }

    private static Table table(Column column) {
        return Table.builder("APP", "CUSTOMER").addColumn(column).build();
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultExpression, int position) {
        return new Column(
                Identifier.of(name), type, nullable, new DefaultValue(defaultExpression),
                Description.empty(), false, position);
    }
}
