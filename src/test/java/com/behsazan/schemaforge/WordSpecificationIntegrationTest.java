package com.behsazan.schemaforge;


import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WordSpecificationIntegrationTest {

    static Stream<Path> wordDocuments() throws Exception {

        Path folder = Path.of("src/test/resources/samples");

        return Files.list(folder)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().endsWith(".docx"))
                .sorted();
    }

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("wordDocuments")
    void shouldProcessWordSpecification(Path inputFile) throws Exception {

        try (InputStream inputStream = Files.newInputStream(inputFile)) {

            SpecificationSource source =
                    new SpecificationSource(
                            inputFile.getFileName().toString(),
                            inputStream);

            DatabaseSchema parsedSchema =
                    new WordSpecificationParser().parse(source);

            assertNotNull(parsedSchema);
            assertFalse(parsedSchema.tables().isEmpty());

            DatabaseSchema normalizedSchema =
                    new SpecificationNormalizer().normalize(parsedSchema);

            ValidationReport report =
                    new SpecificationValidator().validate(normalizedSchema);

            assertTrue(
                    report.valid(),
                    () -> "Validation issues in "
                            + inputFile.getFileName()
                            + " : "
                            + report.issues());

            Path outputFolder =
                    Path.of("target/test-output");

            Files.createDirectories(outputFolder);

            String jsonFileName =
                    inputFile.getFileName()
                            .toString()
                            .replace(".docx", ".json");

            Path outputFile =
                    outputFolder.resolve(jsonFileName);

            new JsonExporter().write(
                    outputFile,
                    normalizedSchema,
                    report);

            assertTrue(Files.exists(outputFile));
            assertTrue(Files.size(outputFile) > 0);

            String sqlFileName =
                    inputFile.getFileName()
                            .toString()
                            .replace(".docx", ".sql");

            Path sqlOutputFile =
                    outputFolder.resolve(sqlFileName);

            String sql = new DdlGenerator(new OracleDialect())
                    .generate(normalizedSchema);

            Files.writeString(sqlOutputFile, sql);

            assertTrue(Files.exists(sqlOutputFile));
            assertTrue(Files.size(sqlOutputFile) > 0);
            assertTrue(sql.contains("CREATE TABLE"));
            assertTrue(sql.contains(normalizedSchema.tables().getFirst()
                    .qualifiedName().name().normalized()));
        }
    }
}