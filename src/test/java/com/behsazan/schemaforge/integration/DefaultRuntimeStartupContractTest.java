package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.SchemaForgeApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Freezes the default runtime contract: optional IBM JCC must not be required
 * unless Db2 LUW metadata access is explicitly enabled.
 */
@SpringBootTest(
        classes = SchemaForgeApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "schemaforge.metadata.oracle.enabled=false",
                "schemaforge.metadata.postgresql.enabled=false",
                "schemaforge.metadata.db2zos.enabled=false",
                "schemaforge.metadata.sqlserver.enabled=false",
                "schemaforge.metadata.mysql.enabled=false",
                "schemaforge.spell-check.enabled=false"
        })
class DefaultRuntimeStartupContractTest {

    @Test
    void applicationContextStartsWithoutOptionalDb2LuwJccDriver() {
        // Context startup is the assertion. Before R11.4.1 this failed with
        // ClassNotFoundException: com.ibm.db2.jcc.DB2Driver.
    }
}
