package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real-corpus test. Supply -Dschemaforge.iam.xmi=C:\path\IAM-TARGET-R0.9.xmi.
 * This IT is deliberately excluded from ordinary surefire class name patterns.
 */
class EnterpriseArchitectIamXmi21CorpusIT {
    @Test
    void importsFullIamModelAndRendersSevenDialectsWithoutSilentConstraintLoss() throws Exception {
        String inputPath = System.getProperty("schemaforge.iam.xmi", "").trim();
        if (inputPath.isBlank()) {
            throw new IllegalArgumentException("Specify -Dschemaforge.iam.xmi=... for IAM acceptance");
        }
        try (var input = Files.newInputStream(Path.of(inputPath))) {
            var schema = new EnterpriseArchitectXmlParser("IAM")
                    .parse("IAM-TARGET-R0.9.xmi", input, "IAM");
            assertEquals(37, schema.tables().size());
            assertEquals(300, schema.tables().stream().mapToInt(table -> table.columns().size()).sum());
            assertEquals(37, schema.tables().stream().filter(table -> table.primaryKey().isPresent()).count());
            assertEquals(30, schema.tables().stream().mapToInt(table -> table.uniqueKeys().size()).sum());
            assertEquals(54, schema.tables().stream().mapToInt(table -> table.foreignKeys().size()).sum());
            assertEquals(90, schema.tables().stream().mapToInt(table -> table.checkConstraints().size()).sum());
            assertEquals(7, schema.findTable("REF_DOMAIN_TYPES").orElseThrow().columns().size());
            assertTrue(schema.metadata().getOrDefault("recovery.warnings", "").isBlank());
            for (DatabasePlatform platform : DatabasePlatform.values()) {
                String sql = new DdlGenerator(DialectFactory.create(platform)).generate(schema);
                assertEquals(37L, Pattern.compile("(?m)^CREATE TABLE ")
                        .matcher(sql).results().count(), platform.toString());
                assertEquals(54L, Pattern.compile("(?m)^ALTER TABLE .* FOREIGN KEY")
                        .matcher(sql).results().count(), platform.toString());
                assertEquals(90L, Pattern.compile("(?m)\\bCHECK\\(")
                        .matcher(sql).results().count(), platform.toString());
            }
        }
    }
}
