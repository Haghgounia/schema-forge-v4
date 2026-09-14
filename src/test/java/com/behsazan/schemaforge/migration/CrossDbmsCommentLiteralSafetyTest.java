package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.CommentLiteralSafety;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossDbmsCommentLiteralSafetyTest {

    private static final String SPECIAL = "مشتری O'Brien & Tax\\Withholding\nLine 2; -- /* literal */ 50% A_B";

    @Test
    void createDdlRendersSpecialCommentTextSafelyForEveryPlatform() {
        Table table = table("Old");
        Table desired = table(SPECIAL);
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(desired).build();

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String sql = new DdlGenerator(DialectFactory.create(platform)).generate(schema);
            assertTrue(sql.contains("O''Brien"), platform.name());
            assertTrue(sql.contains("Line 2; -- /* literal */"), platform.name());
            assertTrue(sql.contains("-- Line 2; -- /* literal */"), "multiline readable header must stay commented for " + platform);
            assertTrue(new SqlScriptStatementParser().parse(sql, platform).stream()
                    .noneMatch(statement -> statement.startsWith("Line 2")),
                    "multiline header must not become executable SQL for " + platform);
            assertTrue(sql.contains("50% A_B"), platform.name());

            switch (platform) {
                case ORACLE -> {
                    assertTrue(sql.startsWith("SET DEFINE OFF"), platform.name());
                    assertTrue(sql.contains("SET SQLBLANKLINES ON"), platform.name());
                    assertTrue(sql.contains("Tax\\Withholding"), platform.name());
                }
                case POSTGRESQL -> assertTrue(sql.contains("E'مشتری O''Brien & Tax\\\\Withholding"), platform.name());
                case SQLSERVER -> assertTrue(sql.contains("@value=N'مشتری O''Brien & Tax\\Withholding"), platform.name());
                case MYSQL, MARIADB -> {
                    assertTrue(sql.contains("SET @SCHEMAFORGE_OLD_SQL_MODE = @@SESSION.sql_mode;"), platform.name());
                    assertTrue(sql.contains("NO_BACKSLASH_ESCAPES"), platform.name());
                    assertTrue(sql.contains("Tax\\\\Withholding"), platform.name());
                    assertTrue(sql.contains("SET SESSION sql_mode = @SCHEMAFORGE_OLD_SQL_MODE;"), platform.name());
                }
                case DB2_ZOS, DB2_LUW -> assertTrue(sql.contains("Tax\\Withholding"), platform.name());
            }
        }
    }

    @Test
    void migrationRendersSpecialCommentTextSafelyForEveryPlatform() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            TableMigrationPlan plan = new SchemaDiffEngine().diff(platform, table("Old"), table(SPECIAL));
            String sql = new MigrationSqlRenderer().render(plan, MigrationRenderOptions.safeDefaults());

            assertTrue(sql.contains("O''Brien"), platform.name());
            assertTrue(sql.contains("Line 2; -- /* literal */"), platform.name());
            switch (platform) {
                case ORACLE -> {
                    assertTrue(sql.startsWith("SET DEFINE OFF"), platform.name());
                    assertTrue(sql.contains("SET SQLBLANKLINES ON"), platform.name());
                }
                case POSTGRESQL -> assertTrue(sql.contains("IS E'مشتری O''Brien & Tax\\\\Withholding"), platform.name());
                case SQLSERVER -> assertTrue(sql.contains("@value=N'مشتری O''Brien & Tax\\Withholding"), platform.name());
                case MYSQL, MARIADB -> {
                    assertTrue(sql.contains("SET @SCHEMAFORGE_OLD_SQL_MODE = @@SESSION.sql_mode;"), platform.name());
                    assertTrue(sql.contains("Tax\\\\Withholding"), platform.name());
                    assertTrue(sql.contains("SET SESSION sql_mode = @SCHEMAFORGE_OLD_SQL_MODE;"), platform.name());
                }
                case DB2_ZOS, DB2_LUW -> assertTrue(sql.contains("Tax\\Withholding"), platform.name());
            }
        }
    }

    @Test
    void unsupportedControlCharactersFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CommentLiteralSafety.standardLiteral("a\u0000b"));
        assertThrows(IllegalArgumentException.class, () -> CommentLiteralSafety.standardLiteral("a\u001Ab"));
    }

    private static Table table(String description) {
        return Table.builder("APP", "CUSTOMER")
                .description(description)
                .addColumn(new Column(
                        Identifier.of("CODE"), DataType.varchar("VARCHAR2", 30), true,
                        new DefaultValue(null), new Description(description), false, 1))
                .build();
    }
}
