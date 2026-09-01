package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.dialect.db2luw.Db2LuwTypeMapper;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosTypeMapper;
import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerTypeMapper;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnspecifiedNumericPrecisionMappingContractTest {

    private static final DataType UNSPECIFIED_NUMBER = DataType.simple("NUMBER");

    @Test
    void canonicalPrecisionRemainsUnspecified() {
        assertNull(UNSPECIFIED_NUMBER.precision());
        assertNull(UNSPECIFIED_NUMBER.scale());
    }

    @Test
    void db2LuwDoesNotFailWhenNumericPrecisionIsMissing() {
        assertEquals("DECIMAL(31,0)", new Db2LuwTypeMapper().map(UNSPECIFIED_NUMBER));
    }

    @Test
    void db2ZosDoesNotFailWhenNumericPrecisionIsMissing() {
        assertEquals("DECIMAL(31,0)", new Db2ZosTypeMapper().map(UNSPECIFIED_NUMBER));
    }

    @Test
    void sqlServerDoesNotFailWhenNumericPrecisionIsMissing() {
        assertEquals("DECIMAL(38,0)", new SqlServerTypeMapper().map(UNSPECIFIED_NUMBER));
    }

    @Test
    void mySqlDoesNotFailWhenNumericPrecisionIsMissing() {
        assertEquals("DECIMAL(65,0)", new MySqlTypeMapper().map(UNSPECIFIED_NUMBER));
    }

    @Test
    void explicitPrecisionMappingsRemainUnchanged() {
        DataType explicit = DataType.numeric("NUMBER", 15, null);

        assertEquals("DECIMAL(15,0)", new Db2LuwTypeMapper().map(explicit));
        assertEquals("DECIMAL(15,0)", new Db2ZosTypeMapper().map(explicit));
        assertEquals("DECIMAL(15,0)", new SqlServerTypeMapper().map(explicit));
        assertEquals("DECIMAL(15)", new MySqlTypeMapper().map(explicit));
    }
}
