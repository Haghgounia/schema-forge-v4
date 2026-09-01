package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
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
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.migration.ColumnChangeKind;
import com.behsazan.schemaforge.migration.MigrationRenderOptions;
import com.behsazan.schemaforge.migration.MigrationRisk;
import com.behsazan.schemaforge.migration.MigrationSqlRenderer;
import com.behsazan.schemaforge.migration.SchemaDiffEngine;
import com.behsazan.schemaforge.migration.TableMigrationPlan;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R11.4 cross-DBMS semantic contract freeze.
 *
 * <p>This gate intentionally validates only semantics already established by the six
 * production dialects. It does not add fallback mappings, synthesize keys, or promote
 * DBA/site-specific physical choices into executable SQL.</p>
 */
class CrossDbmsContractFreezeTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void freezesRegisteredPlatformsAndEssentialCapabilities() {
        assertEquals(List.of("oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql"),
                DatabasePlatform.valuesAsList(EnumSet.allOf(DatabasePlatform.class)));
        assertEquals(6, DatabasePlatform.values().length);

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform);
            assertTrue(dialect.supports(DialectFeature.IDENTITY_COLUMN), platform + " identity capability");
            assertTrue(dialect.supports(DialectFeature.GENERATED_COLUMN), platform + " generated-column capability");
            assertTrue(dialect.supports(DialectFeature.TABLE_COMMENT), platform + " table-comment capability");
            assertTrue(dialect.supports(DialectFeature.COLUMN_COMMENT), platform + " column-comment capability");
            assertTrue(dialect.supports(DialectFeature.GRANT), platform + " grant capability");

            if (platform == DatabasePlatform.MYSQL) {
                assertFalse(dialect.supports(DialectFeature.SEQUENCE), "MySQL standalone sequence contract");
            } else {
                assertTrue(dialect.supports(DialectFeature.SEQUENCE), platform + " sequence capability");
            }
        }
    }

    @Test
    void preservesCoreCanonicalSemanticsAcrossAllSixDialectsAndRemainsDeterministic() {
        DatabaseSchema schema = canonicalContractSchema();

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform);
            String sql = new DdlGenerator(dialect, FIXED_CLOCK).generate(schema);
            String repeated = new DdlGenerator(DialectFactory.create(platform), FIXED_CLOCK).generate(schema);
            String upper = sql.toUpperCase(Locale.ROOT);

            assertEquals(sql, repeated, platform + " DDL must be deterministic for the same model and clock");
            assertTrue(upper.contains("CREATE TABLE"), platform + " CREATE TABLE missing");
            assertTrue(upper.contains("PRIMARY KEY"), platform + " PK missing");
            assertTrue(upper.contains("UNIQUE"), platform + " UK missing");
            assertTrue(upper.contains("CHECK"), platform + " CHECK missing");
            assertTrue(upper.contains("FOREIGN KEY"), platform + " FK missing");
            assertTrue(upper.contains("REFERENCES"), platform + " FK reference missing");
            assertTrue(upper.contains("CREATE INDEX") || upper.contains("CREATE UNIQUE INDEX"),
                    platform + " index missing");
            assertTrue(upper.contains("PK_CONTRACT_PARENT"), platform + " formula PK name missing");
            assertTrue(upper.contains("PK_CONTRACT_PARENT_PARENT_ID") || !dialect.requiresExplicitConstraintIndexes(),
                    platform + " formula PK backing-index name missing when explicit index is required");
            assertTrue(upper.contains("UK_CONTRACT_PARENT_CODE"), platform + " formula UK name missing");
            assertTrue(upper.contains("CHK_CONTRACT_PARENT_STATUS"), platform + " formula CHECK name missing");
            assertTrue(upper.contains("FK_CONTRACT_CHILD_PARENT_ID"), platform + " formula FK name missing");
            assertTrue(upper.contains("IX_CONTRACT_PARENT_STATUS"), platform + " formula index name missing");
            assertTrue(upper.contains("IX_CONTRACT_PARENT_EXTERNAL_CODE"),
                    platform + " formula unique-index name missing");
            assertFalse(upper.contains("SOURCE_PK_NAME"), platform + " leaked input PK name");
            assertFalse(upper.contains("SOURCE_UK_NAME"), platform + " leaked input UK name");
            assertFalse(upper.contains("SOURCE_CHECK_NAME"), platform + " leaked input CHECK name");
            assertFalse(upper.contains("SOURCE_FK_NAME"), platform + " leaked input FK name");
            assertFalse(upper.contains("SOURCE_INDEX_NAME"), platform + " leaked input index name");
            assertFalse(upper.contains("SOURCE_UNIQUE_INDEX_NAME"), platform + " leaked input unique-index name");
            assertTrue(upper.contains("GRANT SELECT"), platform + " grant missing");
            assertTrue(upper.contains("CONTRACT PARENT"), platform + " table description/comment missing");
            assertTrue(upper.contains("PARENT IDENTIFIER"), platform + " column description/comment missing");

            String statusLine = upper.lines()
                    .filter(line -> line.contains("STATUS") && line.contains("DEFAULT"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(platform + " STATUS default column missing"));
            assertTrue(statusLine.contains("NOT NULL"), platform + " required default column lost NOT NULL");
            int defaultPosition = statusLine.indexOf("DEFAULT");
            int notNullPosition = statusLine.indexOf("NOT NULL");
            if (dialect.defaultClauseAfterNullability()) {
                assertTrue(notNullPosition < defaultPosition,
                        platform + " requires NOT NULL before DEFAULT");
            } else {
                assertTrue(defaultPosition < notNullPosition,
                        platform + " requires DEFAULT before NOT NULL");
            }
        }
    }

    @Test
    void unresolvedCanonicalDatatypeFailsClosedForEveryRegisteredPlatform() {
        DatabaseSchema broken = DatabaseSchema.builder("TST")
                .addTable(Table.builder("TST", "BROKEN_TABLE")
                        .addColumn(Column.required("BROKEN_COLUMN", DataType.simple("MISSING_DATA_TYPE")))
                        .build())
                .build();

        var report = new SpecificationValidator().validate(broken);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code().equals("COLUMN_DATATYPE_UNRESOLVED")));

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DdlGenerator(DialectFactory.create(platform), FIXED_CLOCK)
                            .generate(broken, report),
                    platform + " must fail closed for unresolved canonical datatype");
            assertTrue(error.getMessage().contains("Unresolved canonical datatype"));
        }
    }

    @Test
    void generatedPhysicalObjectNamesRemainDeterministicAndTargetBounded() {
        Identifier shortName = Identifier.of("IX_CONTRACT_STATUS");
        String common = "FK_VERY_LONG_SCHEMAFORGE_CONTRACT_TABLE_NAME_WITH_A_LONG_REFERENCE_COLUMN_PREFIX_";
        Identifier firstLong = Identifier.of(common + "FIRST_PARENT_REFERENCE_COLUMN");
        Identifier secondLong = Identifier.of(common + "SECOND_PARENT_REFERENCE_COLUMN");

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertEquals(shortName,
                    PhysicalObjectNamePolicy.physicalIdentifier(platform, shortName),
                    platform + " must preserve representable logical object names");

            Identifier first = PhysicalObjectNamePolicy.physicalIdentifier(platform, firstLong);
            Identifier repeated = PhysicalObjectNamePolicy.physicalIdentifier(platform, firstLong);
            Identifier second = PhysicalObjectNamePolicy.physicalIdentifier(platform, secondLong);

            assertEquals(first, repeated, platform + " physical naming must be deterministic");
            assertTrue(first.value().length() <= PhysicalObjectNamePolicy.maximumLength(platform),
                    platform + " physical name exceeds target limit");
            assertNotEquals(first, second, platform + " long-name shortening must remain collision resistant");
        }
    }


    @Test
    void migrationObjectNamingIgnoresSourceNamesAcrossAllSixDialects() {
        Table live = Table.builder("APP", "NAMING_CHILD")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.required("STATUS", DataType.numeric("NUMBER", 1, 0)))
                .addColumn(Column.required("EXTERNAL_CODE", DataType.varchar("VARCHAR2", 40)))
                .build();

        Table desired = Table.builder("APP", "NAMING_CHILD")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 30)))
                .addColumn(Column.required("STATUS", DataType.numeric("NUMBER", 1, 0)))
                .addColumn(Column.required("EXTERNAL_CODE", DataType.varchar("VARCHAR2", 40)))
                .primaryKey(new PrimaryKey(Identifier.of("BAD_INPUT_PK"), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("BAD_INPUT_UK"), List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(Identifier.of("BAD_INPUT_CHECK"), "STATUS IN (0, 1)"))
                .addIndex(new Index(
                        Identifier.of("BAD_INPUT_INDEX"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addIndex(new Index(
                        Identifier.of("BAD_INPUT_UNIQUE_INDEX"),
                        List.of(new IndexColumn(Identifier.of("EXTERNAL_CODE"), SortDirection.ASC)),
                        IndexType.UNIQUE, Description.empty()))
                .addForeignKey(new ForeignKey(
                        Identifier.of("BAD_INPUT_FK"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of("APP", "NAMING_PARENT"),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, live, desired);
            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            String upper = sql.toUpperCase(Locale.ROOT);

            assertTrue(upper.contains("PK_NAMING_CHILD"), platform + " migration PK formula missing");
            assertTrue(upper.contains("UK_NAMING_CHILD_CODE"), platform + " migration UK formula missing");
            assertTrue(upper.contains("CHK_NAMING_CHILD_STATUS"), platform + " migration CHECK formula missing");
            assertTrue(upper.contains("FK_NAMING_CHILD_PARENT_ID"), platform + " migration FK formula missing");
            assertTrue(upper.contains("IX_NAMING_CHILD_STATUS"), platform + " migration index formula missing");
            assertTrue(upper.contains("IX_NAMING_CHILD_EXTERNAL_CODE"),
                    platform + " migration unique-index formula missing");

            for (String forbidden : List.of(
                    "BAD_INPUT_PK", "BAD_INPUT_UK", "BAD_INPUT_CHECK", "BAD_INPUT_FK",
                    "BAD_INPUT_INDEX", "BAD_INPUT_UNIQUE_INDEX")) {
                assertFalse(upper.contains(forbidden), platform + " migration leaked input name " + forbidden);
            }
        }
    }

    @Test
    void generatedDdlUsesPhysicalTruncateHashAtTargetBoundary() {
        String tableName = "VERY_LONG_SCHEMAFORGE_NAMING_CONTRACT_TABLE";
        String columnName = "VERY_LONG_BUSINESS_REFERENCE_COLUMN_IDENTIFIER";
        Table table = Table.builder("APP", tableName)
                .addColumn(Column.required(columnName, DataType.numeric("NUMBER", 18, 0)))
                .addIndex(new Index(
                        Identifier.of("IGNORED_SOURCE_INDEX_NAME"),
                        List.of(new IndexColumn(Identifier.of(columnName), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();
        Identifier logical = Identifier.of("IX_" + tableName + "_" + columnName);

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(platform, logical);
            String sql = new DdlGenerator(DialectFactory.create(platform), FIXED_CLOCK).generate(schema);
            assertTrue(sql.toUpperCase(Locale.ROOT).contains(physical.normalized()),
                    platform + " DDL does not use the target physical object name");
            assertTrue(physical.value().length() <= PhysicalObjectNamePolicy.maximumLength(platform));
            if (logical.value().length() > PhysicalObjectNamePolicy.maximumLength(platform)) {
                assertTrue(physical.value().matches(".*_[0-9A-F]{12}$"),
                        platform + " long physical name must end with the stable 12-hex hash");
                String executableCreateIndex = sql.lines()
                        .filter(line -> line.toUpperCase(Locale.ROOT).startsWith("CREATE INDEX ")
                                || line.toUpperCase(Locale.ROOT).startsWith("CREATE UNIQUE INDEX "))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(platform + " CREATE INDEX missing"));
                assertFalse(executableCreateIndex.toUpperCase(Locale.ROOT).contains(logical.normalized()),
                        platform + " executable DDL leaked an over-length logical object name");
            } else {
                assertEquals(logical, physical, platform + " representable logical name must be preserved");
            }
        }
    }

    @Test
    void migrationRemainsConvergentAndNeverInfersColumnRename() {
        Table stable = Table.builder("APP", "CONTRACT_M2")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR2", 30)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CONTRACT_M2"), List.of(Identifier.of("ID"))))
                .build();

        Table liveRenameCandidate = Table.builder("APP", "CONTRACT_M2")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.nullable("OLD_CODE", DataType.varchar("VARCHAR2", 30)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CONTRACT_M2"), List.of(Identifier.of("ID"))))
                .build();
        Table desiredRenameCandidate = Table.builder("APP", "CONTRACT_M2")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.nullable("NEW_CODE", DataType.varchar("VARCHAR2", 30)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CONTRACT_M2"), List.of(Identifier.of("ID"))))
                .build();

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertTrue(new SchemaDiffEngine().diff(platform, stable, stable).empty(),
                    platform + " identical live/desired table must converge to zero residual diff");

            TableMigrationPlan plan = new SchemaDiffEngine().diff(
                    platform, liveRenameCandidate, desiredRenameCandidate);
            assertTrue(plan.columnChanges().stream().anyMatch(change ->
                    change.kind() == ColumnChangeKind.ADD_COLUMN
                            && change.columnName().normalized().equals("NEW_CODE")),
                    platform + " missing ADD for rename candidate");
            assertTrue(plan.columnChanges().stream().anyMatch(change ->
                    change.kind() == ColumnChangeKind.DROP_COLUMN
                            && change.columnName().normalized().equals("OLD_CODE")
                            && change.risk() == MigrationRisk.DESTRUCTIVE),
                    platform + " rename must not be guessed; old column must remain a destructive DROP");

            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());
            assertTrue(sql.contains("DROP COLUMN"), platform + " safe migration review must surface DROP COLUMN");
            assertTrue(sql.contains("-- "), platform + " destructive migration SQL must remain commented by default");
        }
    }

    private static DatabaseSchema canonicalContractSchema() {
        Column parentId = new Column(
                Identifier.of("PARENT_ID"), DataType.numeric("NUMBER", 18, 0), false,
                null, new Description("Parent identifier"), false, 1);
        Column code = new Column(
                Identifier.of("CODE"), DataType.varchar("VARCHAR2", 30), false,
                null, new Description("Business code"), false, 2);
        Column status = new Column(
                Identifier.of("STATUS"), DataType.numeric("NUMBER", 1, 0), false,
                new DefaultValue("1"), new Description("Status flag"), false, 3);
        Column externalCode = new Column(
                Identifier.of("EXTERNAL_CODE"), DataType.varchar("VARCHAR2", 40), true,
                null, new Description("External code"), false, 4);

        Table parent = Table.builder("APP", "CONTRACT_PARENT")
                .description("Contract parent")
                .addColumn(parentId)
                .addColumn(code)
                .addColumn(status)
                .addColumn(externalCode)
                .primaryKey(new PrimaryKey(
                        Identifier.of("SOURCE_PK_NAME"), List.of(Identifier.of("PARENT_ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("SOURCE_UK_NAME"), List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(
                        Identifier.of("SOURCE_CHECK_NAME"), "STATUS IN (0, 1)"))
                .addIndex(new Index(
                        Identifier.of("SOURCE_INDEX_NAME"),
                        List.of(new IndexColumn(Identifier.of("STATUS"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addIndex(new Index(
                        Identifier.of("SOURCE_UNIQUE_INDEX_NAME"),
                        List.of(new IndexColumn(Identifier.of("EXTERNAL_CODE"), SortDirection.ASC)),
                        IndexType.UNIQUE, Description.empty()))
                .physicalOption("GRANTS", "SELECT, INSERT, UPDATE, DELETE TO APP_ROLE")
                .build();

        Column childId = new Column(
                Identifier.of("CHILD_ID"), DataType.numeric("NUMBER", 18, 0), false,
                null, new Description("Child identifier"), true, 1);
        Column childParentId = new Column(
                Identifier.of("PARENT_ID"), DataType.numeric("NUMBER", 18, 0), false,
                null, new Description("Parent reference"), false, 2);

        Table child = Table.builder("APP", "CONTRACT_CHILD")
                .description("Contract child")
                .addColumn(childId)
                .addColumn(childParentId)
                .primaryKey(new PrimaryKey(
                        Identifier.of("SOURCE_CHILD_PK"), List.of(Identifier.of("CHILD_ID"))))
                .addForeignKey(new ForeignKey(
                        Identifier.of("SOURCE_FK_NAME"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of("APP", "CONTRACT_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        ReferentialAction.CASCADE,
                        ReferentialAction.NO_ACTION))
                .addIndex(new Index(
                        Identifier.of("SOURCE_CHILD_INDEX"),
                        List.of(new IndexColumn(Identifier.of("PARENT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        return DatabaseSchema.builder("APP")
                .metadata("source.fileName", "cross-dbms-contract-freeze.json")
                .addTable(parent)
                .addTable(child)
                .build();
    }
}
