package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.domain.model.*;
import com.behsazan.schemaforge.domain.valueobject.*;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.validation.constraint.CheckConstraintReferenceAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression against the user's 11-table FCE Enterprise Architect XMI sample. */
class EaFceDdlV060RegressionTest {
    private static final String SOURCE =
            "/ea/v060/FCE_v13_8_84_EA_Oracle_Physical_Corrected_Widths.xml";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T18:00:00Z"), ZoneOffset.UTC);

    private DatabaseSchema sourceModel() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(SOURCE)) {
            assertNotNull(input, "FCE XMI fixture must be bundled");
            return new EnterpriseArchitectXmlParser().parse("FCE.xml", input, "FEE3");
        }
    }

    @Test
    void rendersAllSixtyNineChecksWhereJsonPredicatesAreSupported() throws Exception {
        DatabaseSchema schema = sourceModel();
        assertEquals(11, schema.tables().size());
        assertEquals(166, schema.tables().stream().mapToInt(t -> t.columns().size()).sum());
        assertEquals(69, schema.tables().stream().mapToInt(t -> t.checkConstraints().size()).sum());
        for (Dialect dialect : List.of(new OracleDialect(), new PostgreSqlDialect(),
                new SqlServerDialect(), new MySqlDialect(), new MariaDbDialect())) {
            String sql = new DdlGenerator(dialect, CLOCK).generate(schema);
            assertEquals(69, count(sql, " CHECK("), dialect.name());
            assertFalse(sql.contains("CHECK-COL-001"), dialect.name());
            assertFalse(sql.contains("CHECK-JSON-001"), dialect.name());
        }
    }

    @Test
    void translatesJsonValidationWithoutMaskingRealUnknownColumns() throws Exception {
        DatabaseSchema schema = sourceModel();
        assertEquals(16, schema.tables().stream().flatMap(t -> t.checkConstraints().stream())
                .filter(c -> c.expression().matches("(?i).+ IS JSON")).count());
        for (Table table : schema.tables()) {
            for (CheckConstraint check : table.checkConstraints()) {
                assertTrue(CheckConstraintReferenceAnalyzer.unknownColumns(table, check.expression()).isEmpty(),
                        check.expression());
            }
        }
        assertTrue(new DdlGenerator(new OracleDialect(), CLOCK).generate(schema)
                .contains("FINANCIAL_POLICY_JSON IS JSON"));
        assertTrue(new DdlGenerator(new SqlServerDialect(), CLOCK).generate(schema)
                .contains("ISJSON(FINANCIAL_POLICY_JSON) = 1"));
        assertTrue(new DdlGenerator(new MySqlDialect(), CLOCK).generate(schema)
                .contains("JSON_VALID(`FINANCIAL_POLICY_JSON`) = 1"));
        assertTrue(new DdlGenerator(new PostgreSqlDialect(), CLOCK).generate(schema)
                .contains("financial_policy_json IS JSON"));

        Table incorrect = Table.builder("APP", "X")
                .addColumn(Column.required("AMOUNT", DataType.numeric("NUMBER", 19, 0)))
                .addCheck(new CheckConstraint(Identifier.of("IGNORED"), "MISSING_AMOUNT IS JSON"))
                .build();
        assertTrue(CheckConstraintReferenceAnalyzer.unknownColumns(
                incorrect, "MISSING_AMOUNT IS JSON").contains("MISSING_AMOUNT"));
        String rejected = new DdlGenerator(new OracleDialect(), CLOCK).generate(
                DatabaseSchema.builder("APP").addTable(incorrect).build());
        assertTrue(rejected.contains("CHECK-COL-001"));
    }

    @Test
    void sqlServerProtectsAllAffirmativeJsonColumnsButNotOtherClobs() throws Exception {
        DatabaseSchema schema = sourceModel();
        SqlServerDialect dialect = new SqlServerDialect();
        int jsonCount = 0;
        int nonJsonClobCount = 0;
        for (Table table : schema.tables()) {
            for (Column column : table.columns()) {
                if (!column.dataType().name().normalized().equals("CLOB")) continue;
                boolean affirmativeJsonCheck = table.checkConstraints().stream()
                        .map(check -> com.behsazan.schemaforge.validation.constraint.JsonCheckPredicate
                                .affirmativeJsonColumn(check.expression()))
                        .flatMap(java.util.Optional::stream)
                        .anyMatch(name -> name.equalsIgnoreCase(column.name().value()));
                if (affirmativeJsonCheck) {
                    jsonCount++;
                    assertEquals("NVARCHAR(MAX)", dialect.sqlType(table, column),
                            table.qualifiedName() + "." + column.name());
                } else {
                    nonJsonClobCount++;
                    assertEquals("VARCHAR(MAX)", dialect.sqlType(table, column),
                            table.qualifiedName() + "." + column.name());
                }
            }
        }
        assertEquals(16, jsonCount);
        assertEquals(1, nonJsonClobCount);
        String sql = new DdlGenerator(dialect, CLOCK).generate(schema);
        assertEquals(16, count(sql, " NVARCHAR(MAX)"));
        assertEquals(1, count(sql, " VARCHAR(MAX)"));
        assertEquals(16, count(sql, "ISJSON("));
    }

    @Test
    void sqlServerJsonMappingRequiresAffirmativeConstraintAndPreservesVarcharLength() {
        Table table = Table.builder("DOCS", "DOCUMENTS")
                .addColumn(Column.required("JSON_CONTENT", DataType.simple("CLOB")))
                .addColumn(Column.required("REGULAR_CONTENT", DataType.simple("CLOB")))
                .addColumn(Column.required("INVALID_JSON", DataType.simple("CLOB")))
                .addColumn(Column.required("SMALL_JSON", DataType.varchar("VARCHAR2", 250)))
                .addCheck(new CheckConstraint(Identifier.of("CK_JSON"), "JSON_CONTENT IS JSON"))
                .addCheck(new CheckConstraint(Identifier.of("CK_INVALID"), "INVALID_JSON IS NOT JSON"))
                .addCheck(new CheckConstraint(Identifier.of("CK_SMALL"), "SMALL_JSON IS JSON"))
                .build();
        SqlServerDialect dialect = new SqlServerDialect();
        assertEquals("NVARCHAR(MAX)", dialect.sqlType(table, table.findColumn("JSON_CONTENT").orElseThrow()));
        assertEquals("VARCHAR(MAX)", dialect.sqlType(table, table.findColumn("REGULAR_CONTENT").orElseThrow()));
        assertEquals("VARCHAR(MAX)", dialect.sqlType(table, table.findColumn("INVALID_JSON").orElseThrow()));
        assertEquals("NVARCHAR(250)", dialect.sqlType(table, table.findColumn("SMALL_JSON").orElseThrow()));
    }

    @Test
    void doesNotEmitUnverifiedDb2JsonSqlOrChangeZosBinaryTextPolicy() throws Exception {
        DatabaseSchema schema = sourceModel();
        for (Dialect dialect : List.of(new Db2LuwDialect(), new Db2ZosDialect())) {
            String sql = new DdlGenerator(dialect, CLOCK).generate(schema);
            assertEquals(53, count(sql, " CHECK("), dialect.name());
            assertEquals(16, count(sql, "CHECK-JSON-001"), dialect.name());
            assertFalse(sql.contains("CHECK-COL-001"), dialect.name());
        }
        String zos = new DdlGenerator(new Db2ZosDialect(), CLOCK).generate(schema);
        assertTrue(zos.contains("FOR BIT DATA"));
    }

    @Test
    void preservesOracleNationalTypesAndPostgresNumericIdentityRange() throws Exception {
        DatabaseSchema schema = sourceModel();
        String oracle = new DdlGenerator(new OracleDialect(), CLOCK).generate(schema);
        assertTrue(oracle.contains("NVARCHAR2(250)"));
        assertFalse(oracle.matches("(?s).*NVARCHAR2\\(\\d+\\s+(CHAR|BYTE)\\).*"));

        String pg = new DdlGenerator(new PostgreSqlDialect(), CLOCK).generate(schema);
        assertFalse(pg.contains("NUMERIC(19,0) GENERATED BY DEFAULT AS IDENTITY"));
        assertEquals(8, count(pg, "CREATE SEQUENCE "));
        assertEquals(8, count(pg, "DEFAULT nextval('"));
        assertTrue(pg.contains("NUMERIC(19,0) DEFAULT nextval('fee3.seq_fce_calc_item')"));
    }

    @Test
    void ignoresExternalNamesForPkFkUniqueIndexCheckAndKeepsThemStable() throws Exception {
        DatabaseSchema schema = sourceModel();
        Table ea = schema.findTable("FCE_CALC_ITEM").orElseThrow();
        Table.Builder builder = Table.builder("FEE3", "FCE_CALC_ITEM")
                .persianName(ea.persianName().value())
                .description(ea.description().value());
        ea.columns().forEach(builder::addColumn);
        ea.physicalOptions().forEach(builder::physicalOption);
        ea.primaryKey().ifPresent(pk -> builder.primaryKey(new PrimaryKey(
                Identifier.of("WORD_GENERATED_PK"), pk.columns(), pk.deferrable(),
                pk.initiallyDeferred(), pk.physicalOptions())));
        ea.uniqueKeys().forEach(uk -> builder.addUniqueKey(new UniqueKey(
                Identifier.of("WORD_GENERATED_UK_" + uk.name().value()), uk.columns(),
                uk.deferrable(), uk.initiallyDeferred(), uk.physicalOptions())));
        ea.foreignKeys().forEach(fk -> builder.addForeignKey(new ForeignKey(
                Identifier.of("WORD_GENERATED_FK_" + fk.name().value()), fk.columns(),
                fk.referencedTable(), fk.referencedColumns(), fk.onDelete(), fk.onUpdate(),
                fk.deferrable(), fk.initiallyDeferred(), fk.physicalReference(), fk.schemaExplicit())));
        ea.checkConstraints().forEach(check -> builder.addCheck(new CheckConstraint(
                Identifier.of("WORD_GENERATED_CK_" + check.name().value()), check.expression())));
        ea.indexes().forEach(ix -> builder.addIndex(new Index(
                Identifier.of("WORD_GENERATED_IX_" + ix.name().value()),
                ix.columns(), ix.type(), ix.description(), ix.includeColumns(), ix.predicate(),
                ix.physicalOptions(), ix.buildOptions())));
        Table wordEquivalent = builder.build();
        SpecificationNormalizer normalizer = new SpecificationNormalizer();
        Table normalizedEa = normalizer.normalize(ea);
        Table normalizedWord = normalizer.normalize(wordEquivalent);
        assertEquals(normalizedEa.primaryKey().orElseThrow().name(),
                normalizedWord.primaryKey().orElseThrow().name());
        assertEquals(normalizedEa.foreignKeys().stream().map(ForeignKey::name).toList(),
                normalizedWord.foreignKeys().stream().map(ForeignKey::name).toList());
        assertEquals(normalizedEa.uniqueKeys().stream().map(UniqueKey::name).toList(),
                normalizedWord.uniqueKeys().stream().map(UniqueKey::name).toList());
        assertEquals(normalizedEa.checkConstraints().stream().map(CheckConstraint::name).toList(),
                normalizedWord.checkConstraints().stream().map(CheckConstraint::name).toList());
        assertEquals(normalizedEa.indexes().stream().map(Index::name).toList(),
                normalizedWord.indexes().stream().map(Index::name).toList());
        for (Dialect dialect : List.of(new OracleDialect(), new PostgreSqlDialect(),
                new SqlServerDialect(), new MySqlDialect(), new MariaDbDialect(),
                new Db2LuwDialect(), new Db2ZosDialect())) {
            String eaSql = new DdlGenerator(dialect, CLOCK).generate(
                    DatabaseSchema.builder("FEE3").addTable(ea).build());
            String wordSql = new DdlGenerator(dialect, CLOCK).generate(
                    DatabaseSchema.builder("FEE3").addTable(wordEquivalent).build());
            assertEquals(eaSql, wordSql, "DDL naming must not depend on source object names: " + dialect.name());
        }
    }

    private static int count(String sql, String text) {
        int total = 0;
        for (int i = 0; (i = sql.indexOf(text, i)) >= 0; i += text.length()) total++;
        return total;
    }
}
