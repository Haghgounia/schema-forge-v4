package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders one DBMS-specific SQL deployment from the DBMS-neutral integrated deployment plan.
 *
 * <p>The renderer deliberately delegates individual statement syntax to {@link DdlGenerator}.
 * This keeps Oracle, PostgreSQL and SQL Server syntax identical to the already regression-tested
 * historical pipeline while only changing statement ordering for integrated deployment.</p>
 */
public final class IntegratedSqlRenderer {
    private final Dialect dialect;
    private final DdlGenerator ddlGenerator;
    private final DialectForeignKeyCompatibilityValidator foreignKeyCompatibilityValidator;

    public IntegratedSqlRenderer(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.ddlGenerator = new DdlGenerator(this.dialect);
        this.foreignKeyCompatibilityValidator = new DialectForeignKeyCompatibilityValidator();
    }

    /** Renders all integrated phases without changing the canonical schema or deployment plan. */
    public IntegratedSqlScript render(DatabaseSchema schema, IntegratedSchemaDeploymentPlan plan) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        if (!plan.foreignKeyAnalysis().deployable()) {
            throw new IllegalArgumentException("Cannot render a deployment plan with blocking FK issues");
        }

        Map<String, Table> tablesByName = tablesByName(schema);
        verifyPlanTables(plan, tablesByName);
        List<DialectForeignKeyCompatibilityIssue> compatibilityIssues =
                foreignKeyCompatibilityValidator.validate(schema, plan, dialect);
        if (!compatibilityIssues.isEmpty()) {
            DialectForeignKeyCompatibilityIssue first = compatibilityIssues.getFirst();
            throw new IllegalStateException(
                    "INTEGRATED_RENDER_BLOCKED: " + first.code() + ": " + first.message());
        }

        List<String> phase1 = plan.phase1Tables().stream()
                .map(ddlGenerator::renderIntegratedCreateTable)
                .toList();

        List<String> phase2 = new ArrayList<>();
        for (Table table : plan.phase1Tables()) {
            phase2.addAll(ddlGenerator.renderIntegratedTableLocalStatements(table));
        }

        List<String> phase3 = new ArrayList<>();
        for (ForeignKeyDeployment deployment : plan.phase3ForeignKeys()) {
            Table owner = tablesByName.get(key(deployment.table()));
            if (owner == null) {
                throw new IllegalStateException(
                        "INTEGRATED_RENDER_BLOCKED: foreign-key owner table is missing from schema: "
                                + deployment.table());
            }
            phase3.add(ddlGenerator.renderIntegratedForeignKey(
                    owner, deployment.foreignKey(), deployment.referencedTable()));
        }

        List<String> phase4 = new ArrayList<>();
        for (Table table : plan.phase4MetadataTables()) {
            phase4.addAll(ddlGenerator.renderIntegratedMetadataStatements(table));
        }

        return new IntegratedSqlScript(
                ddlGenerator.renderIntegratedPreTableStatements(schema),
                phase1,
                phase2,
                phase3,
                phase4);
    }

    private static Map<String, Table> tablesByName(DatabaseSchema schema) {
        Map<String, Table> result = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            result.put(key(table.qualifiedName()), table);
        }
        return result;
    }

    private static void verifyPlanTables(
            IntegratedSchemaDeploymentPlan plan, Map<String, Table> tablesByName) {
        for (Table table : plan.phase1Tables()) {
            if (!tablesByName.containsKey(key(table.qualifiedName()))) {
                throw new IllegalStateException(
                        "INTEGRATED_RENDER_BLOCKED: plan contains table not present in schema: "
                                + table.qualifiedName());
            }
        }
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
