package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.generation.enrichment.AuditProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real SQL generator and the user-facing created-last-modified audit contract. */
class AuditTimestampPersianDdlTest {

    @Test
    void defaultAuditColumnsAppearWithPersianNamesInAllSevenDialects() {
        DatabaseSchema ready = prepared(TimestampSemantics.SOURCE);
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String sql = new DdlGenerator(DialectFactory.create(platform)).generate(ready);
            String uppercase = sql.toUpperCase(Locale.ROOT);
            assertTrue(uppercase.contains("CREATED_DATE"), platform.name());
            assertTrue(uppercase.contains("LAST_MODIFIED_DATE"), platform.name());
            assertTrue(sql.contains("تاریخ و زمان ایجاد"), platform.name());
            assertTrue(sql.contains("کاربر ایجادکننده"), platform.name());
            assertTrue(sql.contains("Creation timestamp"), platform.name());
        }
    }

    @Test
    void selectedTimezoneChangesPhysicalAuditSqlForCapableDialects() {
        DatabaseSchema withTimezone = prepared(TimestampSemantics.WITH_TIME_ZONE);
        String oracle = new DdlGenerator(DialectFactory.create(DatabasePlatform.ORACLE)).generate(withTimezone);
        String postgres = new DdlGenerator(DialectFactory.create(DatabasePlatform.POSTGRESQL)).generate(withTimezone);
        String sqlServer = new DdlGenerator(DialectFactory.create(DatabasePlatform.SQLSERVER)).generate(withTimezone);
        assertTrue(oracle.contains("CREATED_DATE TIMESTAMP WITH TIME ZONE"));
        assertTrue(postgres.contains("created_date TIMESTAMP WITH TIME ZONE"));
        assertTrue(sqlServer.contains("DATETIMEOFFSET"));

        DatabaseSchema withLocal = prepared(TimestampSemantics.WITH_LOCAL_TIME_ZONE);
        String localOracle = new DdlGenerator(DialectFactory.create(DatabasePlatform.ORACLE)).generate(withLocal);
        assertTrue(localOracle.contains("CREATED_DATE TIMESTAMP WITH LOCAL TIME ZONE"));
    }

    @Test
    void unsupportedDb2LuwTimezoneUsesRequestedTimestampFallbackWithWarning() {
        DatabaseSchema withTimezone = prepared(TimestampSemantics.WITH_TIME_ZONE);
        String withTimeZoneSql = new DdlGenerator(DialectFactory.create(DatabasePlatform.DB2_LUW))
                .generate(withTimezone);
        assertTrue(withTimeZoneSql.contains("CREATED_DATE TIMESTAMP NOT NULL"));
        assertTrue(withTimeZoneSql.contains("LAST_MODIFIED_DATE TIMESTAMP NOT NULL"));
        assertTrue(withTimeZoneSql.contains("DB2_LUW_TIMEZONE_TIMESTAMP_FALLBACK"));
        DatabaseSchema withLocal = prepared(TimestampSemantics.WITH_LOCAL_TIME_ZONE);
        String localSql = new DdlGenerator(DialectFactory.create(DatabasePlatform.DB2_LUW))
                .generate(withLocal);
        assertTrue(localSql.contains("CREATED_DATE TIMESTAMP NOT NULL"));
        assertTrue(localSql.contains("DB2_LUW_TIMEZONE_TIMESTAMP_FALLBACK"));
    }

    @Test
    void unavailableTimezoneSemanticsUseNativeTimestampForConfiguredAuditColumns() {
        for (TimestampSemantics semantics : new TimestampSemantics[] {
                TimestampSemantics.WITH_TIME_ZONE,
                TimestampSemantics.WITH_LOCAL_TIME_ZONE}) {
            DatabaseSchema ready = prepared(semantics);
            for (DatabasePlatform platform : new DatabasePlatform[] {
                    DatabasePlatform.MYSQL, DatabasePlatform.MARIADB, DatabasePlatform.DB2_LUW}) {
                String sql = new DdlGenerator(DialectFactory.create(platform)).generate(ready);
                String names = sql.replace("`", "").replace("\"", "");
                assertTrue(names.contains("CREATED_DATE TIMESTAMP NOT NULL"), platform.name());
                assertTrue(names.contains("LAST_MODIFIED_DATE TIMESTAMP NOT NULL"), platform.name());
                assertTrue(sql.contains(platform.name() + "_TIMEZONE_TIMESTAMP_FALLBACK"), platform.name());
                assertTrue(sql.contains("تاریخ و زمان ایجاد"), platform.name());
                assertTrue(!sql.contains("VARCHAR(128)"), platform.name());
            }
        }
    }

    private static DatabaseSchema prepared(TimestampSemantics semantics) {
        SpellCheckProperties spell = SpellCheckProperties.defaults();
        spell.setEnabled(false);
        SchemaPreparationService service = new SchemaPreparationService(
                AuditProperties.defaults(), GrantProperties.defaults(), spell,
                NamingValidationProperties.defaults(), new ObjectMapper());
        DatabaseSchema source = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "EVENTS")
                        .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                        .build())
                .build();
        return service.prepare(source,
                new AuditGenerationOptions(true, AuditProfile.CREATED_LAST_MODIFIED),
                semantics).schema();
    }
}
