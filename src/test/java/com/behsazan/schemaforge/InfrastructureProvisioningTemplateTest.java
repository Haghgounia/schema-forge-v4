package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureProvisioningTemplateTest {

    @Test
    void emitsDbmsSpecificInfrastructureGuidanceWithoutGuessingEnvironmentValues() {
        DatabaseSchema schema = DatabaseSchema.builder("FEE")
                .addTable(Table.builder("FEE", "T1")
                        .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                        .build())
                .build();

        String oracle = new DdlGenerator(DialectFactory.create(DatabasePlatform.ORACLE)).generate(schema);
        assertTrue(oracle.contains("[INFRASTRUCTURE TEMPLATE][ORACLE]"));
        assertTrue(oracle.contains("CREATE TABLESPACE TS_FEE"));
        assertTrue(oracle.contains("<DATAFILE_PATH>"));

        String postgres = new DdlGenerator(DialectFactory.create(DatabasePlatform.POSTGRESQL)).generate(schema);
        assertTrue(postgres.contains("[INFRASTRUCTURE TEMPLATE][POSTGRESQL]"));
        assertTrue(postgres.contains("CREATE TABLESPACE <TABLESPACE>"));
        assertTrue(postgres.contains("CREATE SCHEMA IF NOT EXISTS fee"));

        String db2 = new DdlGenerator(DialectFactory.create(DatabasePlatform.DB2_ZOS)).generate(schema);
        assertTrue(db2.contains("[INFRASTRUCTURE TEMPLATE][DB2/ZOS]"));
        assertTrue(db2.contains("CREATE STOGROUP <STOGROUP>"));
        assertTrue(db2.contains("CREATE DATABASE <DATABASE>"));
        assertTrue(db2.contains("CREATE TABLESPACE <TABLESPACE>"));

        String sqlServer = new DdlGenerator(DialectFactory.create(DatabasePlatform.SQLSERVER)).generate(schema);
        assertTrue(sqlServer.contains("[INFRASTRUCTURE TEMPLATE][SQLSERVER]"));
        assertTrue(sqlServer.contains("ADD FILEGROUP [<FILEGROUP>]"));
        assertTrue(sqlServer.contains("CREATE SCHEMA FEE AUTHORIZATION [dbo]"));

        String mysql = new DdlGenerator(DialectFactory.create(DatabasePlatform.MYSQL)).generate(schema);
        assertTrue(mysql.contains("[INFRASTRUCTURE TEMPLATE][MYSQL]"));
        assertTrue(mysql.contains("InnoDB file-per-table"));
        assertTrue(mysql.contains("CREATE DATABASE IF NOT EXISTS `FEE`"));
    }
}
