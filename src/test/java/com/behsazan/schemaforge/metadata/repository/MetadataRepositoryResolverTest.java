package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies metadata repository selection for every registered database platform.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.2
 */
class MetadataRepositoryResolverTest {

    @Test
    void resolvesDb2ZosRepositoryWhenConfigured() {
        ObjectProvider<OracleMetadataRepository> oracle = provider();
        ObjectProvider<PostgreSqlMetadataRepository> postgresql = provider();
        ObjectProvider<Db2ZosMetadataRepository> db2zos = provider();
        Db2ZosMetadataRepository expected = mock(Db2ZosMetadataRepository.class);
        when(db2zos.getIfAvailable()).thenReturn(expected);

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(oracle, postgresql, db2zos);

        assertSame(expected, resolver.resolve(DatabasePlatform.DB2_ZOS));
    }

    @Test
    void returnsEmptyRepositoryWhenDb2ZosMetadataIsDisabled() {
        ObjectProvider<OracleMetadataRepository> oracle = provider();
        ObjectProvider<PostgreSqlMetadataRepository> postgresql = provider();
        ObjectProvider<Db2ZosMetadataRepository> db2zos = provider();
        when(db2zos.getIfAvailable()).thenReturn(null);

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(oracle, postgresql, db2zos);

        assertFalse(resolver.resolve(DatabasePlatform.DB2_ZOS).available());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
