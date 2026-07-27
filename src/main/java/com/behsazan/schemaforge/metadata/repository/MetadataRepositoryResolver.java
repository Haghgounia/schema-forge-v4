package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.springframework.beans.factory.ObjectProvider;
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

    public MetadataRepositoryResolver(ObjectProvider<OracleMetadataRepository> oracle,
                                      ObjectProvider<PostgreSqlMetadataRepository> postgresql) {
        this.oracle = oracle;
        this.postgresql = postgresql;
    }

    public MetadataRepository resolve(DatabasePlatform platform) {
        MetadataRepository repository = switch (platform) {
            case ORACLE -> oracle.getIfAvailable();
            case POSTGRESQL -> postgresql.getIfAvailable();
            case DB2_ZOS -> null;
        };
        return repository == null ? MetadataRepository.empty() : repository;
    }
}
