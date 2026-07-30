package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudProcedureGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Coordinates SQL Server metadata retrieval and CRUD stored-procedure generation. */
@Service
public class SqlServerCrudGenerationService {
    private final MetadataRepositoryResolver repositoryResolver;
    private final SqlServerCrudProcedureGenerator generator;
    private final SqlServerCrudGenerationOptions options;

    @Autowired
    public SqlServerCrudGenerationService(
            MetadataRepositoryResolver repositoryResolver,
            GrantProperties grantProperties) {
        this.repositoryResolver = repositoryResolver;
        this.generator = new SqlServerCrudProcedureGenerator();
        List<String> grantees = grantProperties.getGrants().stream()
                .filter(SqlServerCrudGenerationService::hasWritePrivilege)
                .map(GrantProperties.GrantRule::getGrantee)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        this.options = SqlServerCrudGenerationOptions.ofGrantees(grantees);
    }

    SqlServerCrudGenerationService(
            MetadataRepositoryResolver repositoryResolver,
            SqlServerCrudProcedureGenerator generator,
            SqlServerCrudGenerationOptions options) {
        this.repositoryResolver = repositoryResolver;
        this.generator = generator;
        this.options = options;
    }

    public SqlServerCrudGenerationResult generate(String schemaName, String tableName) {
        String schema = normalizeRequired(schemaName, "schema");
        String table = normalizeRequired(tableName, "table");
        MetadataRepository repository = repositoryResolver.resolve(DatabasePlatform.SQLSERVER);
        if (!repository.available()) {
            throw new IllegalStateException(
                    "SQL Server metadata repository is not enabled; configure schemaforge.metadata.sqlserver");
        }
        Table metadata = repository.findTable(schema, table)
                .orElseThrow(() -> new IllegalArgumentException(
                        "SQL Server table was not found: " + schema + "." + table));
        String sql = generator.generate(metadata, options);
        return new SqlServerCrudGenerationResult(
                schema + "." + table + ".sqlserver.crud-procedures.sql",
                sql);
    }

    private static boolean hasWritePrivilege(GrantProperties.GrantRule rule) {
        if (rule == null || rule.getPrivileges() == null) {
            return false;
        }
        return rule.getPrivileges().stream()
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.equals("INSERT")
                        || value.equals("UPDATE")
                        || value.equals("DELETE"));
    }

    private static String normalizeRequired(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z_][A-Z0-9_$#@]*")) {
            throw new IllegalArgumentException("invalid SQL Server " + label + ": " + value);
        }
        return normalized;
    }

    public record SqlServerCrudGenerationResult(String fileName, String sql) { }
}
