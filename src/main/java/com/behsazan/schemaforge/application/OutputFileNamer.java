package com.behsazan.schemaforge.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** Creates timestamped names for every generated output file. */
public final class OutputFileNamer {
    static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);

    private final Clock clock;

    public OutputFileNamer() {
        this(Clock.systemDefaultZone());
    }

    public OutputFileNamer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Creates the JSON and SQL names of one generation run with one shared timestamp. */
    public OutputNames create(Path outputDirectory, String inputFileName, DatabasePlatform platform) {
        Objects.requireNonNull(platform, "platform must not be null");
        String timestamp = timestamp();
        String outputBaseName = baseName(inputFileName) + "_" + timestamp;

        return new OutputNames(
                outputDirectory(outputDirectory).resolve(outputBaseName + ".json"),
                outputDirectory(outputDirectory).resolve(
                        outputBaseName + "." + platform.commandLineName() + ".sql"),
                timestamp);
    }

    /** Creates a timestamped name for any other generated artifact, such as CSV reports. */
    public Path create(Path outputDirectory, String inputFileName, String extension) {
        Objects.requireNonNull(extension, "extension must not be null");
        String normalizedExtension = extension.startsWith(".") ? extension : "." + extension;
        return outputDirectory(outputDirectory).resolve(
                baseName(inputFileName) + "_" + timestamp() + normalizedExtension);
    }

    private Path outputDirectory(Path outputDirectory) {
        return Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    }

    private String timestamp() {
        return LocalDateTime.now(clock).format(TIMESTAMP_FORMATTER);
    }

    private String baseName(String fileName) {
        Objects.requireNonNull(fileName, "inputFileName must not be null");
        String simpleName = Path.of(fileName).getFileName().toString();
        int dot = simpleName.lastIndexOf('.');
        return dot > 0 ? simpleName.substring(0, dot) : simpleName;
    }

    public record OutputNames(Path jsonFile, Path sqlFile, String timestamp) {
        public OutputNames {
            Objects.requireNonNull(jsonFile, "jsonFile must not be null");
            Objects.requireNonNull(sqlFile, "sqlFile must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
