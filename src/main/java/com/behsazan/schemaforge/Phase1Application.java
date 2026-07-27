package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;

import java.nio.file.Path;

/**
 * Provides the legacy offline command-line entry point for single-document generation.
 *
 * <p>The current supported runtime entry point is {@link SchemaForgeApiApplication}. This
 * class is retained temporarily so existing command-line workflows can be migrated safely.</p>
 *
 * @deprecated Replaced by the REST-based application entry point and scheduled for removal
 *     after compatibility validation.
 * @since 4.0
 */
@Deprecated(since = "4.1", forRemoval = true)
public final class Phase1Application {
    private static final DatabasePlatform DEFAULT_PLATFORM = DatabasePlatform.ORACLE;

    private Phase1Application() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 3) {
            printUsageAndExit();
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        CommandLineOptions options;
        try {
            options = resolveOptions(input, args);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            printUsageAndExit();
            return;
        }

        try {
            GenerationOutput output =
                    new SchemaGenerationService()
                            .generate(input, options.outputDirectory(), options.platform());

            System.out.println("JSON created : " + output.jsonFile());
            System.out.println(output.platform().commandLineName() + " SQL : " + output.sqlFile());
            System.out.println("Validation   : " + (output.valid() ? "VALID" : "INVALID"));
            if (!output.valid()) {
                System.exit(1);
            }
        } catch (Exception exception) {
            System.err.println("SchemaForge failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static CommandLineOptions resolveOptions(Path input, String[] args) {
        Path defaultOutput = input.getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : input.getParent();

        if (args.length == 1) {
            return new CommandLineOptions(defaultOutput, DEFAULT_PLATFORM);
        }
        if (args.length == 2 && isPlatform(args[1])) {
            return new CommandLineOptions(defaultOutput, DatabasePlatform.parse(args[1]));
        }

        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        DatabasePlatform platform = args.length == 3
                ? DatabasePlatform.parse(args[2])
                : DEFAULT_PLATFORM;
        return new CommandLineOptions(outputDirectory, platform);
    }

    private static boolean isPlatform(String value) {
        try {
            DatabasePlatform.parse(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void printUsageAndExit() {
        System.err.println("Usage:");
        System.err.println("  java -jar schema-forge.jar <input.docx>");
        System.err.println("  java -jar schema-forge.jar <input.docx> <oracle|postgresql|db2zos|sqlserver>");
        System.err.println("  java -jar schema-forge.jar <input.docx> <output-directory> [oracle|postgresql|db2zos|sqlserver]");
        System.exit(2);
    }

    record CommandLineOptions(Path outputDirectory, DatabasePlatform platform) {
    }
}
