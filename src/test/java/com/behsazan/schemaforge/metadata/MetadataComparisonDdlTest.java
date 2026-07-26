package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.InMemoryMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataTypeFrequency;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataComparisonDdlTest {

    @Test
    void shouldRenderFrequencyAndAllTypeFrequenciesWhenDocumentTypeDiffers() {
        Column customerId = Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 8, null));
        Table table = Table.builder("BIM", "CUSTOMERS").addColumn(customerId).build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        InMemoryMetadataRepository metadata = new InMemoryMetadataRepository(List.of(
                new MetadataColumnProfile("CUSTOMER_ID", 75, List.of(
                        new MetadataTypeFrequency("NUMBER(10)", 60),
                        new MetadataTypeFrequency("VARCHAR2(20)", 15))))) ;

        String sql = new DdlGenerator(new OracleDialect()).generate(
                schema, new ValidationReport(true, List.of()), metadata);

        assertTrue(sql.contains("/*  75*/  CUSTOMER_ID NUMBER(8) NOT NULL"));
        assertTrue(sql.contains("-- W:TYPE"));
        assertTrue(sql.contains("Metadata frequencies: NUMBER(10) [60], VARCHAR2(20) [15]"));
        assertTrue(sql.contains("Total occurrences: 75"));
    }

    @Test
    void shouldRenderFrequencyWithoutMismatchWhenDocumentTypeExistsInMetadata() {
        Column customerId = Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, null));
        Table table = Table.builder("BIM", "CUSTOMERS").addColumn(customerId).build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        InMemoryMetadataRepository metadata = new InMemoryMetadataRepository(List.of(
                new MetadataColumnProfile("CUSTOMER_ID", 60,
                        List.of(new MetadataTypeFrequency("NUMBER(10)", 60))))) ;

        String sql = new DdlGenerator(new OracleDialect()).generate(
                schema, new ValidationReport(true, List.of()), metadata);

        assertTrue(sql.contains("/*  60*/  CUSTOMER_ID NUMBER(10) NOT NULL"));
        assertTrue(!sql.contains("METADATA_DATATYPE_MISMATCH"));
    }
    @Test
    void shouldRenderZeroFrequencyForNewColumnWhenMetadataIsAvailable() {
        Column censusHousehold = Column.nullable("CENSUS_HOUSEHOLD", DataType.numeric("NUMBER", 8, null));
        Table table = Table.builder("BIM", "PROVINCES").addColumn(censusHousehold).build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        InMemoryMetadataRepository metadata = new InMemoryMetadataRepository(List.of());

        String sql = new DdlGenerator(new OracleDialect()).generate(
                schema, new ValidationReport(true, List.of()), metadata);

        assertTrue(sql.contains("/*   0*/  CENSUS_HOUSEHOLD NUMBER(8)"));
    }

    @Test
    void shouldNotRenderFrequencyWhenMetadataIsUnavailable() {
        Column censusHousehold = Column.nullable("CENSUS_HOUSEHOLD", DataType.numeric("NUMBER", 8, null));
        Table table = Table.builder("BIM", "PROVINCES").addColumn(censusHousehold).build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM").addTable(table).build();

        String sql = new DdlGenerator(new OracleDialect()).generate(
                schema, new ValidationReport(true, List.of()));

        assertTrue(sql.contains("  CENSUS_HOUSEHOLD NUMBER(8)"));
        assertTrue(!sql.contains("/*   0*/  CENSUS_HOUSEHOLD"));
    }

}
