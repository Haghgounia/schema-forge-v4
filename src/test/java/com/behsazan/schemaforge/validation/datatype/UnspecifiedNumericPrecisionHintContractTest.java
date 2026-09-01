package com.behsazan.schemaforge.validation.datatype;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnspecifiedNumericPrecisionHintContractTest {

    @Test
    void allSixDialectsExposeUnspecifiedNumericPrecisionAsWarningNotError() {
        DatabaseSchema schema = schema();

        for (Dialect dialect : List.of(
                new OracleDialect(),
                new PostgreSqlDialect(),
                new Db2ZosDialect(),
                new Db2LuwDialect(),
                new SqlServerDialect(),
                new MySqlDialect())) {

            var assessment = new DatatypeCompatibilityAnalyzer().analyze(schema, dialect);
            var matching = assessment.issues().stream()
                    .filter(issue -> "NUMERIC_PRECISION_UNSPECIFIED".equals(issue.code()))
                    .toList();

            assertEquals(1, matching.size(), dialect.name());
            assertEquals("WARNING", matching.get(0).severity(), dialect.name());
            assertTrue(matching.get(0).path().endsWith(".columns.ProfileID"), dialect.name());
            assertTrue(matching.get(0).message().contains("explicit precision"), dialect.name());

            assertFalse(
                    assessment.issues().stream()
                            .anyMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())
                                    && issue.path().endsWith(".columns.ProfileID")),
                    dialect.name());
        }
    }

    private static DatabaseSchema schema() {
        Column profileId = new Column(
                Identifier.of("ProfileID"),
                DataType.simple("NUMBER"),
                true,
                new DefaultValue("20"),
                new Description(""),
                false,
                1,
                null);

        Table table = Table.builder("TSTSHMA", "CTETopUpTransaction")
                .addColumn(profileId)
                .build();

        return DatabaseSchema.builder("TSTSHMA")
                .addTable(table)
                .build();
    }
}
