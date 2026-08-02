package com.behsazan.schemaforge.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Central naming policy for every generated SchemaForge artifact. */
public final class OutputFileNamer {
    static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\d{8}_\\d{6}_\\d{3}");

    private final Clock clock;

    public OutputFileNamer() {
        this(Clock.systemDefaultZone());
    }

    public OutputFileNamer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Creates the JSON and DDL names of one generation run with one shared timestamp. */
    public OutputNames create(Path outputDirectory, String inputFileName, DatabasePlatform platform) {
        Objects.requireNonNull(platform, "platform must not be null");
        String timestamp = timestamp();
        String outputBaseName = baseName(inputFileName);

        return new OutputNames(
                outputDirectory(outputDirectory).resolve(outputBaseName + "_" + timestamp + ".json"),
                outputDirectory(outputDirectory).resolve(
                        scriptFileName(outputBaseName, platform, ScriptKind.DDL, timestamp)),
                timestamp);
    }

    /** Creates a timestamped name for any non-SQL artifact, such as CSV reports. */
    public Path create(Path outputDirectory, String inputFileName, String extension) {
        Objects.requireNonNull(extension, "extension must not be null");
        String normalizedExtension = extension.startsWith(".") ? extension : "." + extension;
        if (".sql".equalsIgnoreCase(normalizedExtension)) {
            throw new IllegalArgumentException(
                    "SQL scripts must be named through scriptFileName(...)");
        }
        return outputDirectory(outputDirectory).resolve(
                baseName(inputFileName) + "_" + timestamp() + normalizedExtension);
    }

    /**
     * The single naming rule for all generated SQL scripts.
     *
     * <p>Format:</p>
     * <pre>
     * logical-name_timestamp.platform.sql
     * logical-name_timestamp.platform.crud-package.sql
     * logical-name_timestamp.platform.crud-procedures.sql
     * logical-name_timestamp.platform.run-all.sql
     * </pre>
     */
    public String scriptFileName(
            String logicalName,
            DatabasePlatform platform,
            ScriptKind kind,
            String timestamp) {

        String normalizedName = logicalName(logicalName);
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        validateTimestamp(timestamp);

        String artifact = switch (kind) {
            case DDL -> "";
            case RUN_ALL -> ".run-all";
            case CRUD -> switch (platform) {
                case ORACLE -> ".crud-package";
                case SQLSERVER -> ".crud-procedures";
                default -> throw new IllegalArgumentException(
                        "CRUD script naming is not supported for platform: " + platform);
            };
        };

        return normalizedName
                + "_" + timestamp
                + "." + platform.commandLineName()
                + artifact
                + ".sql";
    }

    /** Creates one timestamp that can be shared by every artifact of one request. */
    public String timestamp() {
        return LocalDateTime.now(clock).format(TIMESTAMP_FORMATTER);
    }

    private Path outputDirectory(Path outputDirectory) {
        return Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    }

    private String logicalName(String value) {
        Objects.requireNonNull(value, "logicalName must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("logicalName must not be blank");
        }
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("logicalName must not contain a path: " + value);
        }
        return normalized;
    }

    private void validateTimestamp(String value) {
        Objects.requireNonNull(value, "timestamp must not be null");
        if (!TIMESTAMP_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "timestamp must match yyyyMMdd_HHmmss_SSS: " + value);
        }
    }

    private String baseName(String fileName) {
        Objects.requireNonNull(fileName, "inputFileName must not be null");
        String simpleName = Path.of(fileName).getFileName().toString();
        int dot = simpleName.lastIndexOf('.');
        return dot > 0 ? simpleName.substring(0, dot) : simpleName;
    }

    public enum ScriptKind {
        DDL,
        CRUD,
        RUN_ALL
    }

    public record OutputNames(Path jsonFile, Path sqlFile, String timestamp) {
        public OutputNames {
            Objects.requireNonNull(jsonFile, "jsonFile must not be null");
            Objects.requireNonNull(sqlFile, "sqlFile must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
