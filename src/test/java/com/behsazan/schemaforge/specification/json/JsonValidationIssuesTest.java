package com.behsazan.schemaforge.specification.json;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonValidationIssuesTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteRecoveryDuplicateMetadataAndSpellingIssues() throws Exception {
        Table table = Table.builder("BIM", "PROVINCES")
                .addColumn(Column.required("IS_ACTIVE", DataType.numeric("NUMBER", 1, null)))
                .addColumn(Column.required("PROVINCE_CODE", DataType.numeric("NUMBER", 10, null)))
                .addColumn(Column.required("PROVINC_NAME", DataType.varchar("VARCHAR2", 50)))
                .build();

        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .metadata("recovery.warnings",
                        "Normalized datatype text 'IDENTITY NUMBER (2)' to 'NUMBER (2)'\n"
                                + "DUPLICATE_COLUMN|name=IS_ACTIVE|firstRow=10|duplicateRow=11|definition=IS_ACTIVE NUMBER(1) DEFAULT 1 NOT NULL")
                .addTable(table)
                .build();

        ValidationReport report = new ValidationReport(true, List.of(
                new ValidationIssue("WARNING", "METADATA_DATATYPE_MISMATCH",
                        "tables.PROVINCES.columns.PROVINCE_CODE", "type mismatch"),
                new ValidationIssue("WARNING", "SPELLING_WARNING",
                        "tables.PROVINCES.columns.PROVINC_NAME", "spelling warning")));

        Path output = tempDir.resolve("provinces.json");
        new JsonExporter().write(output, schema, report);

        JsonNode issues = new ObjectMapper().readTree(output.toFile()).path("validation").path("issues");
        Set<String> codes = StreamSupport.stream(issues.spliterator(), false)
                .map(issue -> issue.path("code").asText())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "RECOVERY_WARNING",
                "DUPLICATE_COLUMN",
                "METADATA_DATATYPE_MISMATCH",
                "SPELLING_WARNING"), codes);
    }
}
