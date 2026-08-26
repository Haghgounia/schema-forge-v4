package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnresolvedCanonicalDatatypeGenerationTest {

    @Test
    void unresolvedDatatypeIsInvalidAndFailsClosedForEveryDialect() {
        DatabaseSchema schema = DatabaseSchema.builder("TST")
                .addTable(Table.builder("TST", "BROKEN_TABLE")
                        .addColumn(Column.required("BROKEN_COLUMN", DataType.simple("MISSING_DATA_TYPE")))
                        .build())
                .build();

        var report = new SpecificationValidator().validate(schema);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code().equals("COLUMN_DATATYPE_UNRESOLVED")));

        List<Dialect> dialects = List.of(
                new OracleDialect(),
                new PostgreSqlDialect(),
                new Db2ZosDialect(),
                new SqlServerDialect(),
                new MySqlDialect());

        for (Dialect dialect : dialects) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DdlGenerator(dialect).generate(schema, report));
            assertTrue(error.getMessage().contains("Unresolved canonical datatype"));
        }
    }
}
