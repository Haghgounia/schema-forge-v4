package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.oracle.OracleCrudPackageGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Coordinates Oracle metadata retrieval and CRUD package generation. */
@Service
public class OracleCrudGenerationService {
    private final MetadataRepositoryResolver repositoryResolver;
    private final OracleCrudPackageGenerator generator;
    private final OracleCrudGenerationOptions options;
    private final ArtifactNamingPolicy artifactNamingPolicy;

    @Autowired
    public OracleCrudGenerationService(
            MetadataRepositoryResolver repositoryResolver,
            GrantProperties grantProperties) {
        this.repositoryResolver = repositoryResolver;
        this.generator = new OracleCrudPackageGenerator();
        this.artifactNamingPolicy = new ArtifactNamingPolicy();
        List<String> grantees = grantProperties.getGrants().stream()
                .filter(OracleCrudGenerationService::hasWritePrivilege)
                .map(GrantProperties.GrantRule::getGrantee)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        this.options = OracleCrudGenerationOptions.ofGrantees(grantees);
    }

    OracleCrudGenerationService(
            MetadataRepositoryResolver repositoryResolver,
            OracleCrudPackageGenerator generator,
            OracleCrudGenerationOptions options) {
        this(repositoryResolver, generator, options, new OutputFileNamer());
    }

    OracleCrudGenerationService(
            MetadataRepositoryResolver repositoryResolver,
            OracleCrudPackageGenerator generator,
            OracleCrudGenerationOptions options,
            OutputFileNamer outputFileNamer) {
        this.repositoryResolver = repositoryResolver;
        this.generator = generator;
        this.options = options;
        this.artifactNamingPolicy = new ArtifactNamingPolicy(outputFileNamer);
    }

    public OracleCrudGenerationResult generate(String schemaName, String tableName) {
        String schema = normalizeRequired(schemaName, "schema");
        String table = normalizeRequired(tableName, "table");
        MetadataRepository repository = repositoryResolver.resolve(DatabasePlatform.ORACLE);
        if (!repository.available()) {
            throw new IllegalStateException(
                    "Oracle metadata repository is not enabled; configure schemaforge.metadata.oracle");
        }
        Table metadata = repository.findTable(schema, table)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Oracle table was not found: " + schema + "." + table));
        String sql = generator.generate(metadata, options);
        String logicalName = schema + "." + table;
        String timestamp = artifactNamingPolicy.timestamp();
        String fileName = artifactNamingPolicy.crudFileName(
                logicalName, DatabasePlatform.ORACLE, timestamp);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.DATABASE_METADATA, logicalName, timestamp);
        context.ledger().generated(context, ArtifactType.CRUD, DatabasePlatform.ORACLE,
                logicalName, fileName, "application/sql", "OracleCrudPackageGenerator");
        ArtifactDescriptor descriptor = context.ledger().snapshot().getFirst();
        return new OracleCrudGenerationResult(fileName, sql, descriptor);
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
        if (!normalized.matches("[A-Z][A-Z0-9_$#]*")) {
            throw new IllegalArgumentException("invalid Oracle " + label + ": " + value);
        }
        return normalized;
    }

    public record OracleCrudGenerationResult(
            String fileName, String sql, ArtifactDescriptor artifact) {
        public OracleCrudGenerationResult(String fileName, String sql) {
            this(fileName, sql, null);
        }
    }
}
