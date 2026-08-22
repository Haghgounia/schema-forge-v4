package com.behsazan.schemaforge.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Provides metadata data source configuration functionality within the SchemaForge processing pipeline.
 *
 * @since 4.1
 */
@Configuration
public class MetadataDataSourceConfiguration {

    @Bean("oracleMetadataDataSource")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.oracle", name = "enabled", havingValue = "true")
    DataSource oracleMetadataDataSource(MetadataProperties properties) {
        return create(properties.getOracle(), "Oracle");
    }

    @Bean("oracleMetadataJdbcTemplate")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.oracle", name = "enabled", havingValue = "true")
    NamedParameterJdbcTemplate oracleMetadataJdbcTemplate(
            @Qualifier("oracleMetadataDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean("postgresqlMetadataDataSource")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.postgresql", name = "enabled", havingValue = "true")
    DataSource postgresqlMetadataDataSource(MetadataProperties properties) {
        return create(properties.getPostgresql(), "PostgreSQL");
    }

    @Bean("postgresqlMetadataJdbcTemplate")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.postgresql", name = "enabled", havingValue = "true")
    NamedParameterJdbcTemplate postgresqlMetadataJdbcTemplate(
            @Qualifier("postgresqlMetadataDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean("db2ZosMetadataDataSource")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.db2zos", name = "enabled", havingValue = "true")
    DataSource db2ZosMetadataDataSource(MetadataProperties properties) {
        return create(properties.getDb2zos(), "Db2 for z/OS");
    }

    @Bean("db2ZosMetadataJdbcTemplate")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.db2zos", name = "enabled", havingValue = "true")
    NamedParameterJdbcTemplate db2ZosMetadataJdbcTemplate(
            @Qualifier("db2ZosMetadataDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean("sqlServerMetadataDataSource")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.sqlserver", name = "enabled", havingValue = "true")
    DataSource sqlServerMetadataDataSource(MetadataProperties properties) {
        return create(properties.getSqlserver(), "SQL Server");
    }

    @Bean("sqlServerMetadataJdbcTemplate")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.sqlserver", name = "enabled", havingValue = "true")
    NamedParameterJdbcTemplate sqlServerMetadataJdbcTemplate(
            @Qualifier("sqlServerMetadataDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }


    @Bean("mySqlMetadataDataSource")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.mysql", name = "enabled", havingValue = "true")
    DataSource mySqlMetadataDataSource(MetadataProperties properties) {
        return create(properties.getMysql(), "MySQL");
    }

    @Bean("mySqlMetadataJdbcTemplate")
    @ConditionalOnProperty(prefix = "schemaforge.metadata.mysql", name = "enabled", havingValue = "true")
    NamedParameterJdbcTemplate mySqlMetadataJdbcTemplate(
            @Qualifier("mySqlMetadataDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    private static DataSource create(MetadataProperties.Database properties, String databaseName) {
        require(properties.getUrl(), databaseName + " metadata URL");
        require(properties.getUsername(), databaseName + " metadata username");
        require(properties.getDriverClassName(), databaseName + " JDBC driver class");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        return dataSource;
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must be configured when metadata validation is enabled");
        }
    }
}
