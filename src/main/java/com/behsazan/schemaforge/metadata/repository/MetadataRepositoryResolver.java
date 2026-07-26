package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
        };
        return repository == null ? MetadataRepository.empty() : repository;
    }
}
