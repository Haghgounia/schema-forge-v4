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
        profiles.put(token, new Profile(platform, repository, verified, expiresAt,
                preferred, suggestions, schemaListTruncated));
        return new Selection(token, platform.commandLineName(), verified, expiresAt.toString(),
                preferred, suggestions, schemaListTruncated);
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
                profile.schemaListTruncated);
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

    public record Selection(String token, String platform, String verifiedSchema, String expiresAt,
                            String defaultSchema, List<String> availableSchemas, boolean schemaListTruncated) {
        public Selection {
            availableSchemas = availableSchemas == null ? List.of() : List.copyOf(availableSchemas);
        }
        public Selection(String token, String platform, String verifiedSchema, String expiresAt) {
            this(token, platform, verifiedSchema, expiresAt, "", List.of(), false);
        }
    }
    private record Profile(DatabasePlatform platform, MetadataRepository repository,
                           String schema, Instant expiresAt, String defaultSchema,
                           List<String> availableSchemas, boolean schemaListTruncated) { }
}
