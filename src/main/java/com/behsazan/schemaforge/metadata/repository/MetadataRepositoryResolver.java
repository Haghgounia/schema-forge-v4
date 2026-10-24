package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;

/**
 * Resolves the appropriate metadata repository implementation at runtime.
 *
 * @since 4.1
 */
@Component
public class MetadataRepositoryResolver {
    private final ObjectProvider<OracleMetadataRepository> oracle;
    private final ObjectProvider<PostgreSqlMetadataRepository> postgresql;
    private final ObjectProvider<Db2ZosMetadataRepository> db2zos;
    private final ObjectProvider<Db2LuwMetadataRepository> db2luw;
    private final ObjectProvider<SqlServerMetadataRepository> sqlserver;
    private final ObjectProvider<MySqlMetadataRepository> mysql;
    private final ObjectProvider<MariaDbMetadataRepository> mariadb;
    private final RuntimeMetadataConnectionRegistry runtimeConnections;

    @Autowired
    public MetadataRepositoryResolver(ObjectProvider<OracleMetadataRepository> oracle,
                                      ObjectProvider<PostgreSqlMetadataRepository> postgresql,
                                      ObjectProvider<Db2ZosMetadataRepository> db2zos,
                                      ObjectProvider<Db2LuwMetadataRepository> db2luw,
                                      ObjectProvider<SqlServerMetadataRepository> sqlserver,
                                      ObjectProvider<MySqlMetadataRepository> mysql,
                                      ObjectProvider<MariaDbMetadataRepository> mariadb,
                                      RuntimeMetadataConnectionRegistry runtimeConnections) {
        this.oracle = oracle;
        this.postgresql = postgresql;
        this.db2zos = db2zos;
        this.db2luw = db2luw;
        this.sqlserver = sqlserver;
        this.mysql = mysql;
        this.mariadb = mariadb;
        this.runtimeConnections = runtimeConnections;
    }

    /** Existing constructor retained for isolated repository tests and non-Spring consumers. */
    public MetadataRepositoryResolver(ObjectProvider<OracleMetadataRepository> oracle,
                                      ObjectProvider<PostgreSqlMetadataRepository> postgresql,
                                      ObjectProvider<Db2ZosMetadataRepository> db2zos,
                                      ObjectProvider<Db2LuwMetadataRepository> db2luw,
                                      ObjectProvider<SqlServerMetadataRepository> sqlserver,
                                      ObjectProvider<MySqlMetadataRepository> mysql,
                                      ObjectProvider<MariaDbMetadataRepository> mariadb) {
        this(oracle, postgresql, db2zos, db2luw, sqlserver, mysql, mariadb, null);
    }

    public MetadataRepository resolve(DatabasePlatform platform) {
        if (runtimeConnections != null) {
            RequestAttributes request = RequestContextHolder.getRequestAttributes();
            if (request instanceof ServletRequestAttributes servlet) {
                String token = servlet.getRequest().getHeader(RuntimeMetadataConnectionRegistry.HEADER);
                if (token != null && !token.isBlank()) {
                    return runtimeConnections.resolve(token, platform);
                }
            }
        }

        MetadataRepository repository = switch (platform) {
            case ORACLE -> oracle.getIfAvailable();
            case POSTGRESQL -> postgresql.getIfAvailable();
            case DB2_ZOS -> db2zos.getIfAvailable();
            case DB2_LUW -> db2luw.getIfAvailable();
            case SQLSERVER -> sqlserver.getIfAvailable();
            case MYSQL -> mysql.getIfAvailable();
            case MARIADB -> mariadb.getIfAvailable();
        };
        return repository == null ? MetadataRepository.empty() : repository;
    }
}
