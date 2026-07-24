package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationReport;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** The only executable entry point for phase 1: Word -> normalize -> validate -> JSON. */
public final class Phase1Application {
    private Phase1Application() { }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -jar schema-forge-phase1.jar <input.docx> [output.json]");
            System.exit(2);
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : defaultOutput(input);

        try {
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("Input file does not exist: " + input);
            }
            Files.createDirectories(output.getParent());

            DatabaseSchema parsed;
            try (InputStream stream = Files.newInputStream(input)) {
                parsed = new WordSpecificationParser().parse(
                        new SpecificationSource(input.getFileName().toString(), stream));
            }

            DatabaseSchema normalized = new SpecificationNormalizer().normalize(parsed);
            ValidationReport report = new SpecificationValidator().validate(normalized);
            new JsonExporter().write(output, normalized, report);

            System.out.println("JSON created: " + output);
            System.out.println("Validation: " + (report.valid() ? "VALID" : "INVALID"));
            if (!report.valid()) System.exit(1);
        } catch (Exception exception) {
            System.err.println("Phase 1 failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path defaultOutput(Path input) {
        String fileName = input.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return input.resolveSibling(base + ".json");
    }
}
