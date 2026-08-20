package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlDirectoryExecutionSyntaxTest {
    @Test
    void dropTableUsesMysqlIfExistsSyntax() {
        assertEquals("DROP TABLE IF EXISTS `TSTSHMA`.`ACCOUNT`",
                MySqlDirectoryExecutionTest.dropTableSql("`TSTSHMA`.`ACCOUNT`"));
    }
}
