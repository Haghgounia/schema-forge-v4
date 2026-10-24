package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.config.MetadataProperties;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Read-only view of metadata defaults, bounded JDBC probes and per-browser metadata selection.
 * No credentials are persisted to disk or installed into application-wide DataSources.
 */
@Service
public final class DatabaseConnectionAdministrationService {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_JDBC_URL = 1500;
    private final MetadataProperties properties;
    private final RuntimeMetadataConnectionRegistry runtimeConnections;
    // Bound concurrent probes; daemon workers cannot prevent JVM shutdown if a vendor driver ignores interruption.
    private final ExecutorService probes = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "schemaforge-jdbc-probe");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public DatabaseConnectionAdministrationService(MetadataProperties properties,
            RuntimeMetadataConnectionRegistry runtimeConnections) {
        this.properties = Objects.requireNonNull(properties);
        this.runtimeConnections = Objects.requireNonNull(runtimeConnections);
    }

    public List<ConnectionSummary> summaries() {
        return List.of(Platform.values()).stream().map(platform -> {
            MetadataProperties.Database db = config(platform);
            return new ConnectionSummary(platform.code, platform.label, db.isEnabled(),
                    publicJdbcUrl(db.getUrl()), safe(db.getUsername()), platform.driver,
                    db.getPassword() != null && !db.getPassword().isBlank(), available(platform.driver));
        }).toList();
    }

    /** Probes are read-only and do not register a runtime connection. */
    public ProbeResult probe(ProbeRequest request) {
        if (request == null) throw new IllegalArgumentException("Connection test input is required");
        Platform platform = Platform.parse(request.platform());
        String url = checked(request.jdbcUrl(), "JDBC URL", MAX_JDBC_URL);
        String username = checked(request.username(), "Username", 128);
        if (!url.toLowerCase(java.util.Locale.ROOT).startsWith(platform.prefix)) {
            throw new IllegalArgumentException("The JDBC URL is not valid for the selected DBMS");
        }
        // Avoid credentials in URLs: these would be visible in copied configuration, logs, or browser history.
        if (containsUrlCredentials(url)) {
            throw new IllegalArgumentException("Put credentials in the separate username/password fields, not in the URL");
        }
        if (request.useConfiguredPassword()) {
            throw new IllegalArgumentException(
                    "Please enter your own JDBC password; server credentials are not reusable from the UI");
        }
        String suppliedPassword = request.password();
        if (suppliedPassword != null && (suppliedPassword.length() > 1024 || hasControlChars(suppliedPassword))) {
            throw new IllegalArgumentException("Invalid password field");
        }
        String password = suppliedPassword == null ? "" : suppliedPassword;
        String schema = request.schema() == null ? "" : request.schema().trim();
        if (!schema.isEmpty()) checkedSchema(schema);
        if (!available(platform.driver)) {
            return new ProbeResult(false, "DRIVER_UNAVAILABLE", "درایور JDBC در این اجرای Backend موجود نیست.", "", "", 0);
        }
        long started = System.nanoTime();
        Future<ProbeResult> result;
        try {
            result = probes.submit(() -> connect(platform, url, username, password, schema, started));
        } catch (RejectedExecutionException exception) {
            return new ProbeResult(false, "BUSY", "آزمون‌های اتصال هم‌زمان زیاد است؛ بعداً دوباره آزمایش کنید.", "", "", 0);
        }
        try {
            return result.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            result.cancel(true);
            return new ProbeResult(false, "TIMEOUT", "مهلت آزمون اتصال به پایان رسید.", "", "", elapsed(started));
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "INTERRUPTED", "آزمون اتصال متوقف شد.", "", "", elapsed(started));
        } catch (ExecutionException exception) {
            return new ProbeResult(false, "CONNECTION_FAILED", "ارتباط JDBC برقرار نشد؛ تنظیمات و دسترسی شبکه را بررسی کنید.",
                    "", "", elapsed(started));
        }
    }

    /**
     * Activates a runtime connection after a read-only metadata probe. A schema is optional:
     * when supplied, its permissions are checked by the vendor repository; otherwise
     * JDBC schema/catalog discovery is required and per-table rights are checked later.
     */
    public RuntimeMetadataConnectionRegistry.Selection activate(ProbeRequest request) {
        ProbeResult result = probe(request);
        if (!result.success()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "JDBC/metadata validation failed: " + result.code());
        }
        DatabasePlatform platform = DatabasePlatform.parse(request.platform());
        return runtimeConnections.register(platform, request.jdbcUrl().trim(),
                request.username().trim(), request.password() == null ? "" : request.password(),
                request.schema() == null ? "" : request.schema().trim(),
                result.defaultSchema(), result.availableSchemas(), result.schemaListTruncated(),
                result.databaseName(), result.product(), result.version());
    }

    /** Explicit selection of an enabled startup profile, no browser credential round-trip. */
    public RuntimeMetadataConnectionRegistry.Selection activateConfigured(String code) {
        Platform platform = Platform.parse(code);
        MetadataProperties.Database db = config(platform);
        if (!db.isEnabled() || db.getUrl() == null || db.getUrl().isBlank()
                || db.getUsername() == null || db.getUsername().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Configured metadata connection is disabled or lacks URL/username: " + platform.code);
        }
        ProbeRequest configured = new ProbeRequest(platform.code, db.getUrl(), db.getUsername(),
                db.getPassword() == null ? "" : db.getPassword(), false, "");
        // The same bounded live catalog probe is required for built-in and custom profiles.
        return activate(configured);
    }

    public RuntimeMetadataConnectionRegistry.Selection selected(String token) {
        return runtimeConnections.describe(token);
    }

    public void disconnect(String token) {
        runtimeConnections.disconnect(token);
    }

    private static ProbeResult connect(Platform platform, String url, String username,
                                       String password, String schema, long started) {
        try {
            Class.forName(platform.driver);
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                DatabaseMetaData meta = connection.getMetaData();
                String product = truncate(meta.getDatabaseProductName());
                String version = truncate(meta.getDatabaseProductVersion());
                String databaseName = "";
                try { databaseName = truncate(connection.getCatalog()); }
                catch (SQLException | AbstractMethodError ignored) { /* Some drivers do not expose catalog. */ }
                if (!schema.isEmpty()) {
                    // Both catalog checks use the same vendor-specific, read-only repository as
                    // Conformance/CRUD/Generation, not an unrelated generic JDBC test.
                    MetadataRepository repository = RuntimeMetadataConnectionRegistry.createRepository(
                            DatabasePlatform.parse(platform.code), url, username, password);
                    if (!repository.schemaExists(schema)) {
                        return new ProbeResult(false, "SCHEMA_NOT_VISIBLE",
                                "Selected schema was not found or is not visible to this account.",
                                product, version, elapsed(started), "", List.of(), false, databaseName);
                    }
                    repository.findTableNames(schema);
                    return new ProbeResult(true, "METADATA_READY",
                            "JDBC and schema metadata access verified with read-only catalog queries.",
                            product, version, elapsed(started), "", List.of(), false, databaseName);
                }
                JdbcSchemaDiscovery.Result discovery = JdbcSchemaDiscovery.inspect(connection, meta, platform.code);
                if (!discovery.metadataReadable() || discovery.availableSchemas().isEmpty()) {
                    return new ProbeResult(false, "METADATA_DISCOVERY_UNAVAILABLE",
                            "JDBC login succeeded but no database schemas could be enumerated. "
                                    + "Enter a schema and test its metadata access explicitly.",
                            product, version, elapsed(started), "", List.of(), false, databaseName);
                }
                return new ProbeResult(true, "METADATA_DISCOVERED",
                        "JDBC metadata discovery succeeded. Schema selection is optional here; "
                                + "individual table permissions are checked when metadata is requested.",
                        product, version, elapsed(started), discovery.defaultSchema(),
                        discovery.availableSchemas(), discovery.truncated(), databaseName);
            }
        } catch (SQLException exception) {
            String sqlState = exception.getSQLState();
            String code = sqlState != null && sqlState.startsWith("28") ? "AUTH_FAILED" : "CONNECTION_FAILED";
            return new ProbeResult(false, code,
                    "AUTH_FAILED".equals(code) ? "Database authentication was denied."
                            : "JDBC connection or metadata read failed; check network, driver and privileges.",
                    "", "", elapsed(started));
        } catch (RuntimeException exception) {
            return new ProbeResult(false, "METADATA_UNAVAILABLE",
                    "JDBC metadata cannot be read with these credentials and schema.",
                    "", "", elapsed(started));
        } catch (Exception | LinkageError exception) {
            return new ProbeResult(false, "CONNECTION_FAILED", "JDBC connection failed.",
                    "", "", elapsed(started));
        }
    }

    private static String checkedSchema(String schema) {
        if (schema.length() > 128 || !schema.matches("[A-Za-z_][A-Za-z0-9_$#-]*")) {
            throw new IllegalArgumentException("Invalid metadata schema identifier");
        }
        return schema;
    }

    private MetadataProperties.Database config(Platform platform) {
        return switch (platform) {
            case ORACLE -> properties.getOracle();
            case POSTGRESQL -> properties.getPostgresql();
            case SQLSERVER -> properties.getSqlserver();
            case MYSQL -> properties.getMysql();
            case MARIADB -> properties.getMariadb();
            case DB2LUW -> properties.getDb2luw();
            case DB2ZOS -> properties.getDb2zos();
        };
    }

    private static boolean available(String driver) {
        try { Class.forName(driver); return true; }
        catch (ClassNotFoundException | LinkageError exception) { return false; }
    }
    private static String checked(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max || hasControlChars(value)) {
            throw new IllegalArgumentException(label + " is required and must be well formed");
        }
        return value.trim();
    }
    private static boolean hasControlChars(String value) {
        return value.chars().anyMatch(c -> c < 32 || c == 127);
    }
    private static boolean containsUrlCredentials(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.matches("(?is).*([;?&](?:password|pwd|user|uid)\\s*=|://[^/;?]+:[^/;?]+@).*");
    }
    private static String publicJdbcUrl(String value) {
        return containsUrlCredentials(value) ? "[REDACTED: JDBC URL contains credentials]" : safe(value);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String truncate(String value) { return safe(value).length() > 120 ? value.substring(0, 120) : safe(value); }
    private static long elapsed(long nanos) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos); }

    @PreDestroy public void shutdown() { probes.shutdownNow(); }

    public record ConnectionSummary(String platform, String displayName, boolean enabled, String jdbcUrl,
                                    String username, String driverClassName, boolean passwordConfigured, boolean driverAvailable) { }
    public record ProbeRequest(String platform, String jdbcUrl, String username, String password,
                               boolean useConfiguredPassword, String schema) {
        public ProbeRequest(String platform, String jdbcUrl, String username, String password,
                            boolean useConfiguredPassword) {
            this(platform, jdbcUrl, username, password, useConfiguredPassword, null);
        }
    }
    public record ProbeResult(boolean success, String code, String message, String product, String version,
                              long elapsedMillis, String defaultSchema, List<String> availableSchemas,
                              boolean schemaListTruncated, String databaseName) {
        public ProbeResult {
            defaultSchema = defaultSchema == null ? "" : defaultSchema;
            databaseName = databaseName == null ? "" : databaseName;
            availableSchemas = availableSchemas == null ? List.of() : List.copyOf(availableSchemas);
        }
        public ProbeResult(boolean success, String code, String message, String product, String version,
                           long elapsedMillis) {
            this(success, code, message, product, version, elapsedMillis, "", List.of(), false, "");
        }
        public ProbeResult(boolean success, String code, String message, String product, String version,
                           long elapsedMillis, String defaultSchema, List<String> availableSchemas,
                           boolean schemaListTruncated) {
            this(success, code, message, product, version, elapsedMillis,
                    defaultSchema, availableSchemas, schemaListTruncated, "");
        }
    }

    private enum Platform {
        ORACLE("oracle", "Oracle", "jdbc:oracle:", "oracle.jdbc.OracleDriver"),
        POSTGRESQL("postgresql", "PostgreSQL", "jdbc:postgresql:", "org.postgresql.Driver"),
        SQLSERVER("sqlserver", "SQL Server", "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
        MYSQL("mysql", "MySQL", "jdbc:mysql:", "com.mysql.cj.jdbc.Driver"),
        MARIADB("mariadb", "MariaDB", "jdbc:mariadb:", "org.mariadb.jdbc.Driver"),
        DB2LUW("db2luw", "DB2 LUW", "jdbc:db2:", "com.ibm.db2.jcc.DB2Driver"),
        DB2ZOS("db2zos", "DB2 z/OS", "jdbc:db2:", "com.ibm.db2.jcc.DB2Driver");
        final String code, label, prefix, driver;
        Platform(String code, String label, String prefix, String driver) {
            this.code = code; this.label = label; this.prefix = prefix; this.driver = driver;
        }
        static Platform parse(String code) {
            for (Platform item : values()) if (item.code.equals(code)) return item;
            throw new IllegalArgumentException("Unsupported database platform");
        }
    }
}
