package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, per-browser runtime metadata connections. Nothing is written to
 * application.yml or H2; no connection is ever installed globally.
 */
@Component
public final class RuntimeMetadataConnectionRegistry {
    public static final String HEADER = "X-SchemaForge-Metadata-Connection";
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final int MAX_PROFILES = 48;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

    public synchronized Selection register(DatabasePlatform platform, String url, String username,
                                           String password, String schema) {
        return register(platform, url, username, password, schema, "", List.of(), false);
    }

    public synchronized Selection register(DatabasePlatform platform, String url, String username,
                                           String password, String schema, String defaultSchema,
                                           List<String> availableSchemas, boolean schemaListTruncated) {
        return register(platform, url, username, password, schema, defaultSchema,
                availableSchemas, schemaListTruncated, "", "", "");
    }

    /** Carries a safe, immutable display identity alongside the transient repository. */
    public synchronized Selection register(DatabasePlatform platform, String url, String username,
                                           String password, String schema, String defaultSchema,
                                           List<String> availableSchemas, boolean schemaListTruncated,
                                           String databaseName, String product, String productVersion) {
        prune();
        if (profiles.size() >= MAX_PROFILES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many active metadata connections; disconnect an existing session");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(LIFETIME);
        MetadataRepository repository = createRepository(platform, url, username, password);
        List<String> suggestions = availableSchemas == null ? List.of() : List.copyOf(availableSchemas);
        String preferred = defaultSchema == null ? "" : defaultSchema;
        String verified = schema == null ? "" : schema;
        String target = safeJdbcTarget(url);
        String database = safe(databaseName);
        if (database.isBlank() && platform == DatabasePlatform.SQLSERVER) {
            database = sqlServerDatabaseName(url);
        }
        profiles.put(token, new Profile(platform, repository, verified, expiresAt,
                preferred, suggestions, schemaListTruncated, target, database, safe(username),
                safe(product), safe(productVersion)));
        return new Selection(token, platform.commandLineName(), verified, expiresAt.toString(),
                preferred, suggestions, schemaListTruncated, target, database,
                safe(username), safe(product), safe(productVersion));
    }

    public MetadataRepository resolve(String token, DatabasePlatform requestedPlatform) {
        Profile profile = requireProfile(token);
        if (profile.platform != requestedPlatform) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The selected metadata connection belongs to " + profile.platform.commandLineName()
                            + "; select only that DBMS or choose another connection");
        }
        return profile.repository;
    }

    public Selection describe(String token) {
        Profile profile = requireProfile(token);
        return new Selection("", profile.platform.commandLineName(), profile.schema,
                profile.expiresAt.toString(), profile.defaultSchema, profile.availableSchemas,
                profile.schemaListTruncated, profile.jdbcTarget, profile.databaseName,
                profile.username, profile.product, profile.productVersion);
    }

    public void disconnect(String token) {
        if (token != null && !token.isBlank()) profiles.remove(token);
    }

    private Profile requireProfile(String token) {
        Profile profile = token == null ? null : profiles.get(token);
        if (profile == null || !profile.expiresAt.isAfter(Instant.now())) {
            if (token != null) profiles.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Metadata connection expired or not found; reconnect from the Connections screen");
        }
        return profile;
    }

    private void prune() {
        Instant now = Instant.now();
        profiles.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    /** Reuses all seven existing vendor-specific metadata SQL implementations. */
    public static MetadataRepository createRepository(DatabasePlatform platform, String url,
                                                      String username, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        return switch (platform) {
            case ORACLE -> new JdbcOracleMetadataRepository(jdbc);
            case POSTGRESQL -> new JdbcPostgreSqlMetadataRepository(jdbc);
            case SQLSERVER -> new JdbcSqlServerMetadataRepository(jdbc);
            case MYSQL -> new JdbcMySqlMetadataRepository(jdbc);
            case MARIADB -> new JdbcMariaDbMetadataRepository(jdbc);
            case DB2_LUW -> new JdbcDb2LuwMetadataRepository(jdbc);
            case DB2_ZOS -> new JdbcDb2ZosMetadataRepository(jdbc);
        };
    }

    /** No JDBC password or arbitrary JDBC properties are returned to the browser. */
    private static String safeJdbcTarget(String url) {
        if (url == null || url.isBlank()) return "";
        String target = url.trim();
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        int properties = target.indexOf(';');
        if (properties >= 0) target = target.substring(0, properties);
        // Do not accidentally disclose URL-embedded credentials in a display field.
        if (target.matches("(?is).*://[^/;?]+:[^/;?]+@.*")
                || target.matches("(?is)jdbc:oracle:thin:[^@]+/[^@]+@.*")
                || target.matches("(?is).*(?:password|pwd|secret|token)\\s*=.*")) {
            return "[REDACTED: sensitive JDBC target]";
        }
        return target.length() <= 220 ? target : target.substring(0, 220) + "...";
    }


    /** SQL Server often supplies its database in ;databaseName= rather than the URL path. */
    private static String sqlServerDatabaseName(String url) {
        if (url == null) return "";
        var match = java.util.regex.Pattern.compile("(?i)(?:^|;)databaseName\\s*=\\s*([^;?]+)").matcher(url);
        if (!match.find()) return "";
        String name = match.group(1).trim();
        return name.length() <= 128 && name.chars().noneMatch(c -> c < 32 || c == 127) ? name : "";
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public record Selection(String token, String platform, String verifiedSchema, String expiresAt,
                            String defaultSchema, List<String> availableSchemas, boolean schemaListTruncated,
                            String jdbcTarget, String databaseName, String username,
                            String databaseProduct, String databaseVersion) {
        public Selection {
            availableSchemas = availableSchemas == null ? List.of() : List.copyOf(availableSchemas);
            defaultSchema = safe(defaultSchema);
            verifiedSchema = safe(verifiedSchema);
            jdbcTarget = safe(jdbcTarget);
            databaseName = safe(databaseName);
            username = safe(username);
            databaseProduct = safe(databaseProduct);
            databaseVersion = safe(databaseVersion);
        }
        public Selection(String token, String platform, String verifiedSchema, String expiresAt) {
            this(token, platform, verifiedSchema, expiresAt, "", List.of(), false,
                    "", "", "", "", "");
        }
    }
    private record Profile(DatabasePlatform platform, MetadataRepository repository,
                           String schema, Instant expiresAt, String defaultSchema,
                           List<String> availableSchemas, boolean schemaListTruncated,
                           String jdbcTarget, String databaseName, String username,
                           String product, String productVersion) { }
}
