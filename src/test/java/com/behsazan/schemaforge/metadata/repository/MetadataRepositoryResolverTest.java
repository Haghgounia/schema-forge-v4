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

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(oracle, postgresql, db2zos, provider(), provider(), provider());

        assertSame(expected, resolver.resolve(DatabasePlatform.DB2_ZOS));
    }

    @Test
    void returnsEmptyRepositoryWhenDb2ZosMetadataIsDisabled() {
        ObjectProvider<OracleMetadataRepository> oracle = provider();
        ObjectProvider<PostgreSqlMetadataRepository> postgresql = provider();
        ObjectProvider<Db2ZosMetadataRepository> db2zos = provider();
        when(db2zos.getIfAvailable()).thenReturn(null);

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(oracle, postgresql, db2zos, provider(), provider(), provider());

        assertFalse(resolver.resolve(DatabasePlatform.DB2_ZOS).available());
    }

    @Test
    void resolvesDb2LuwRepositoryWhenConfigured() {
        ObjectProvider<Db2LuwMetadataRepository> db2luw = provider();
        Db2LuwMetadataRepository expected = mock(Db2LuwMetadataRepository.class);
        when(db2luw.getIfAvailable()).thenReturn(expected);

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), db2luw, provider(), provider());

        assertSame(expected, resolver.resolve(DatabasePlatform.DB2_LUW));
    }

    @Test
    void returnsEmptyRepositoryWhenDb2LuwMetadataIsDisabled() {
        ObjectProvider<Db2LuwMetadataRepository> db2luw = provider();
        when(db2luw.getIfAvailable()).thenReturn(null);

        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), db2luw, provider(), provider());

        assertFalse(resolver.resolve(DatabasePlatform.DB2_LUW).available());
    }

    @Test
    void resolvesSqlServerRepositoryWhenConfigured() {
        ObjectProvider<SqlServerMetadataRepository> sqlserver = provider();
        SqlServerMetadataRepository expected = mock(SqlServerMetadataRepository.class);
        when(sqlserver.getIfAvailable()).thenReturn(expected);
        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), provider(), sqlserver, provider());

        assertSame(expected, resolver.resolve(DatabasePlatform.SQLSERVER));
    }

    @Test
    void returnsEmptyRepositoryWhenSqlServerMetadataIsDisabled() {
        ObjectProvider<SqlServerMetadataRepository> sqlserver = provider();
        when(sqlserver.getIfAvailable()).thenReturn(null);
        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), provider(), sqlserver, provider());

        assertFalse(resolver.resolve(DatabasePlatform.SQLSERVER).available());
    }


    @Test
    void resolvesMySqlRepositoryWhenConfigured() {
        ObjectProvider<MySqlMetadataRepository> mysql = provider();
        MySqlMetadataRepository expected = mock(MySqlMetadataRepository.class);
        when(mysql.getIfAvailable()).thenReturn(expected);
        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), provider(), provider(), mysql);

        assertSame(expected, resolver.resolve(DatabasePlatform.MYSQL));
    }

    @Test
    void returnsEmptyRepositoryWhenMySqlMetadataIsDisabled() {
        ObjectProvider<MySqlMetadataRepository> mysql = provider();
        when(mysql.getIfAvailable()).thenReturn(null);
        MetadataRepositoryResolver resolver = new MetadataRepositoryResolver(
                provider(), provider(), provider(), provider(), provider(), mysql);

        assertFalse(resolver.resolve(DatabasePlatform.MYSQL).available());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
