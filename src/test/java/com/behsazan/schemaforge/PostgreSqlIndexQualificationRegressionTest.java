package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fast regression guard for PostgreSQL CREATE INDEX name qualification. */
class PostgreSqlIndexQualificationRegressionTest {

    @Test
    void dialectMustNeverQualifyPostgreSqlIndexNameWithSchema() {
        PostgreSqlDialect dialect = new PostgreSqlDialect();
        assertEquals("ix_probe",
                dialect.qualifyIndexName(QualifiedName.of("TSTSHMA", "PROBE"), "ix_probe"));
    }

    @Test
    void generatorMustQualifyOnlyTheTableName() {
        Table table = Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(new Column(Identifier.of("ID"), DataType.numeric("NUMBER", 18, 0),
                        false, null, Description.empty(), false, 1))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_ID"),
                        List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();

        String sql = new DdlGenerator(new PostgreSqlDialect()).generate(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build());

        assertTrue(sql.contains("CREATE INDEX ix_customer_id ON tstshma.customer(id)"));
        assertFalse(sql.contains("CREATE INDEX tstshma.ix_customer_id"));
    }
}
