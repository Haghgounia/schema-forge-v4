package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Defines external configuration properties for metadata.
 *
 * @since 4.1
 */
@ConfigurationProperties(prefix = "schemaforge.metadata")
public class MetadataProperties {
    private final Database oracle = new Database();
    private final Database postgresql = new Database();
    private final Database db2zos = new Database();
    private final Database db2luw = new Database();
    private final Database sqlserver = new Database();
    private final Database mysql = new Database();

    public Database getOracle() { return oracle; }
    public Database getPostgresql() { return postgresql; }
    public Database getDb2zos() { return db2zos; }
    public Database getDb2luw() { return db2luw; }
    public Database getSqlserver() { return sqlserver; }
    public Database getMysql() { return mysql; }

    public static class Database {
        private boolean enabled;
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    }
}
