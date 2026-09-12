package com.behsazan.schemaforge.validation.mariadb;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbDdlSanityCheckerTest {
    private final MariaDbDdlSanityChecker checker = new MariaDbDdlSanityChecker();

    @Test
    void acceptsSchemaForgeGeneratedMariaDbDdl() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.nullable("NAME", DataType.varchar("VARCHAR2", 100)))
                .primaryKey(new PrimaryKey(Identifier.of("SOURCE_PK"), List.of(Identifier.of("ID"))))
                .build();
        String sql = new DdlGenerator(new MariaDbDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());

        assertTrue(checker.inspect(sql).isEmpty());
        assertDoesNotThrow(() -> checker.requireValid(sql, "customer.mariadb.sql"));
    }

    @Test
    void acceptsSerialWhenItIsOnlyAQuotedIdentifier() {
        String sql = """
                CREATE TABLE `APP`.`CHEQUE` (`SERIAL` BIGINT NOT NULL, `VALUE` DECIMAL(18,2));
                CREATE INDEX `IX_CHEQUE_SERIAL` ON `APP`.`CHEQUE`(`SERIAL`);
                """;

        assertTrue(checker.inspect(sql).isEmpty());
    }

    @Test
    void stillRejectsPostgresSerialDatatype() {
        String sql = "CREATE TABLE `APP`.`T` (`ID` SERIAL NOT NULL);";

        Set<String> codes = checker.inspect(sql).stream()
                .map(MariaDbDdlSanityChecker.Issue::code)
                .collect(Collectors.toSet());

        assertTrue(codes.contains("MARIADB_POSTGRES_SERIAL"));
    }

    @Test
    void rejectsCrossDbmsSyntaxAndUnsupportedMariaDbShapes() {
        String sql = """
                CREATE TABLESPACE `TS_APP` ADD DATAFILE 'x.ibd';
                CREATE TABLE `APP`.`T` (`ID` NUMBER(20), `TS` DATETIME(9));
                CREATE INDEX `APP`.`IX_T_ID` ON `APP`.`T`(`ID`) INCLUDE (`TS`) WHERE `ID` > 0;
                ALTER TABLE `APP`.`T` ADD CONSTRAINT `FK_T` FOREIGN KEY (`ID`) REFERENCES `APP`.`P`(`ID`) ON DELETE SET DEFAULT DEFERRABLE;
                """;

        Set<String> codes = checker.inspect(sql).stream()
                .map(MariaDbDdlSanityChecker.Issue::code)
                .collect(Collectors.toSet());

        assertTrue(codes.contains("MARIADB_CREATE_TABLESPACE"));
        assertTrue(codes.contains("MARIADB_ORACLE_NUMBER"));
        assertTrue(codes.contains("MARIADB_TEMPORAL_PRECISION"));
        assertTrue(codes.contains("MARIADB_SCHEMA_QUALIFIED_INDEX_NAME"));
        assertTrue(codes.contains("MARIADB_INDEX_INCLUDE"));
        assertTrue(codes.contains("MARIADB_PARTIAL_INDEX"));
        assertTrue(codes.contains("MARIADB_REFERENTIAL_SET_DEFAULT"));
        assertTrue(codes.contains("MARIADB_DEFERRABLE"));
    }

    @Test
    void rejectsMariaDbTargetLimitViolations() {
        String longIdentifier = "X".repeat(65);
        String sql = "CREATE TABLE `APP`.`" + longIdentifier
                + "` (`A` DECIMAL(66,39), `B` VARCHAR(20000), `C` TIME(7), `D` CHAR(256));";

        Set<String> codes = checker.inspect(sql).stream()
                .map(MariaDbDdlSanityChecker.Issue::code)
                .collect(Collectors.toSet());

        assertTrue(codes.contains("MARIADB_IDENTIFIER_LENGTH"));
        assertTrue(codes.contains("MARIADB_DECIMAL_PRECISION"));
        assertTrue(codes.contains("MARIADB_DECIMAL_SCALE"));
        assertTrue(codes.contains("MARIADB_UTF8MB4_VARCHAR_LENGTH"));
        assertTrue(codes.contains("MARIADB_FIXED_CHAR_LENGTH"));
        assertTrue(codes.contains("MARIADB_TEMPORAL_PRECISION"));
    }

    @Test
    void requireValidFailsClosedWhenInvalidSqlLeaksThrough() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> checker.requireValid(
                        "CREATE TABLE `APP`.`T` (`ID` VARCHAR2(30));", "bad.mariadb.sql"));

        assertTrue(exception.getMessage().contains("MariaDB DDL sanity check failed"));
        assertTrue(exception.getMessage().contains("MARIADB_ORACLE_VARCHAR2"));
    }
}
