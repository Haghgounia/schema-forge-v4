package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.config.MetadataProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * Read-only view of effective JDBC metadata settings and explicit, bounded connection probes.
 * Does not persist credentials or change the DataSources already instantiated at startup.
 */
@Service
public final class DatabaseConnectionAdministrationService {
    public static final String ADMIN_HEADER = "X-SchemaForge-Connections-Admin-Key";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_JDBC_URL = 1500;
    private final MetadataProperties properties;
    private final String adminKey;
    // Bound concurrent probes; daemon workers cannot prevent JVM shutdown if a vendor driver ignores interruption.
    private final ExecutorService probes = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "schemaforge-jdbc-probe");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public DatabaseConnectionAdministrationService(MetadataProperties properties,
            @org.springframework.beans.factory.annotation.Value("${schemaforge.connections.admin-key:}") String adminKey) {
        this.properties = Objects.requireNonNull(properties);
        this.adminKey = adminKey == null ? "" : adminKey;
    }

    public void authorize(String supplied) {
        if (adminKey.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Connection administration requires SCHEMAFORGE_CONNECTIONS_ADMIN_KEY");
        }
        if (supplied == null || !MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid connection administrator key");
        }
    }

    public List<ConnectionSummary> summaries() {
        return List.of(Platform.values()).stream().map(platform -> {
            MetadataProperties.Database db = config(platform);
            return new ConnectionSummary(platform.code, platform.label, db.isEnabled(),
                    publicJdbcUrl(db.getUrl()), safe(db.getUsername()), platform.driver,
                    db.getPassword() != null && !db.getPassword().isBlank(), available(platform.driver));
        }).toList();
    }

    /** A probe never saves credentials, selects a platform, alters a repository, or runs DDL. */
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
        String suppliedPassword = request.password();
        if (suppliedPassword != null && (suppliedPassword.length() > 1024 || hasControlChars(suppliedPassword))) {
            throw new IllegalArgumentException("Invalid password field");
        }
        MetadataProperties.Database configured = config(platform);
        String password;
        if (request.useConfiguredPassword()) {
            // Prevent a privileged credential being forwarded to a different, user-supplied host/account.
            if (!url.equals(configured.getUrl()) || !username.equals(configured.getUsername())
                    || (suppliedPassword != null && !suppliedPassword.isEmpty())) {
                throw new IllegalArgumentException(
                        "Configured password is available only for the unchanged configured URL and username");
            }
            password = safe(configured.getPassword());
        } else {
            password = suppliedPassword == null ? "" : suppliedPassword;
        }
        if (!available(platform.driver)) {
            return new ProbeResult(false, "DRIVER_UNAVAILABLE", "درایور JDBC در این اجرای Backend موجود نیست.", "", "", 0);
        }
        long started = System.nanoTime();
        Future<ProbeResult> result;
        try {
            result = probes.submit(() -> connect(platform.driver, url, username, password, started));
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

    private static ProbeResult connect(String driver, String url, String username, String password, long started) {
        try {
            Class.forName(driver);
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                DatabaseMetaData meta = connection.getMetaData();
                return new ProbeResult(true, "CONNECTED", "اتصال JDBC برقرار شد؛ هیچ SQL یا DDL اجرا نشد.",
                        truncate(meta.getDatabaseProductName()), truncate(meta.getDatabaseProductVersion()), elapsed(started));
            }
        } catch (SQLException exception) {
            String sqlState = exception.getSQLState();
            String code = sqlState != null && sqlState.startsWith("28") ? "AUTH_FAILED" : "CONNECTION_FAILED";
            return new ProbeResult(false, code,
                    "AUTH_FAILED".equals(code) ? "احراز هویت دیتابیس رد شد." : "اتصال برقرار نشد؛ شبکه، آدرس و تنظیمات JDBC را بررسی کنید.",
                    "", "", elapsed(started));
        } catch (Exception | LinkageError exception) {
            return new ProbeResult(false, "CONNECTION_FAILED", "ارتباط JDBC برقرار نشد.", "", "", elapsed(started));
        }
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
                               boolean useConfiguredPassword) { }
    public record ProbeResult(boolean success, String code, String message, String product, String version,
                              long elapsedMillis) { }

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
