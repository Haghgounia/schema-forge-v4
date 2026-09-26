package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * DBMS-independent naming validation for canonical schemas.
 *
 * <p>Naming findings belong to the canonical model and are intentionally evaluated once,
 * before per-dialect metadata comparison. Foreign-key references do not repeat the naming
 * finding of the referenced table.</p>
 *
 * @since 4.1
 */
public final class NamingConventionValidator {
    private static final Set<String> S_ENDING_SINGULAR_WORDS = Set.of(
            "STATUS", "SUCCESS", "ADDRESS", "PROCESS", "CLASS", "BUSINESS", "ACCESS",
            "ANALYSIS", "BASIS", "CRISIS", "DIAGNOSIS", "EMPHASIS", "THESIS");
    private static final Set<String> VALID_NON_S_PLURAL_TABLE_WORDS = Set.of(
            "DATA", "METADATA", "INFORMATION");
    private static final Set<String> ACCEPTABLE_S_ENDING_COLUMN_COMPONENTS = Set.of(
            "STATUS", "SUCCESS", "ADDRESS", "PROCESS", "CLASS", "BUSINESS", "ACCESS",
            "ANALYSIS", "BASIS", "CRISIS", "DIAGNOSIS", "EMPHASIS", "THESIS",
            // Domain/grammar false positives observed in real EA corpora.
            "EXPIRES", "REQUIRES", "DETAILS", "FUNDS", "DAYS");

    private final NamingValidationProperties properties;

    public NamingConventionValidator(NamingValidationProperties properties) {
        this.properties = Objects.requireNonNull(properties, "naming properties must not be null");
    }

    public List<ValidationIssue> validate(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            validateTableName(table, issues);
            validateColumnComponents(table, issues);
        }
        return List.copyOf(issues);
    }

    private void validateTableName(Table table, List<ValidationIssue> issues) {
        NamingValidationProperties.TableConvention convention = properties.getTableConvention();
        if (convention == NamingValidationProperties.TableConvention.OFF) {
            return;
        }

        String tableName = table.qualifiedName().name().value();
        boolean plural = looksLikePluralTableName(tableName);
        if (convention == NamingValidationProperties.TableConvention.PLURAL && !plural) {
            issues.add(new ValidationIssue(
                    "WARNING",
                    "TABLE_NAME_NOT_PLURAL",
                    tablePath(table),
                    "Table name " + tableName + " appears to be singular. Table names should be plural."));
        } else if (convention == NamingValidationProperties.TableConvention.SINGULAR && plural) {
            issues.add(new ValidationIssue(
                    "WARNING",
                    "TABLE_NAME_NOT_SINGULAR",
                    tablePath(table),
                    "Table name " + tableName + " appears to be plural. Table names should be singular."));
        }
    }

    private void validateColumnComponents(Table table, List<ValidationIssue> issues) {
        if (!properties.isPluralColumnComponentsEnabled()) {
            return;
        }
        for (Column column : table.columns()) {
            Set<String> pluralParts = new LinkedHashSet<>();
            for (String part : column.name().value().toUpperCase(Locale.ROOT).split("_")) {
                if (looksPluralColumnComponent(part)) {
                    pluralParts.add(part);
                }
            }
            if (!pluralParts.isEmpty()) {
                issues.add(new ValidationIssue(
                        "WARNING",
                        "PLURAL_COLUMN_COMPONENT",
                        columnPath(table, column),
                        "Plural identifier component(s) detected: " + String.join(", ", pluralParts) + "."));
            }
        }
    }

    static boolean looksLikePluralTableName(String identifier) {
        String[] parts = identifier.toUpperCase(Locale.ROOT).split("_");
        String word = parts[parts.length - 1];
        if (VALID_NON_S_PLURAL_TABLE_WORDS.contains(word)) {
            return true;
        }
        if (S_ENDING_SINGULAR_WORDS.contains(word)) {
            return false;
        }
        return word.endsWith("S");
    }

    static boolean looksPluralColumnComponent(String word) {
        if (word.length() < 4 || ACCEPTABLE_S_ENDING_COLUMN_COMPONENTS.contains(word)) {
            return false;
        }
        if (word.endsWith("SS") || word.endsWith("US") || word.endsWith("IS")) {
            return false;
        }
        return word.endsWith("S");
    }

    private static String tablePath(Table table) {
        return "tables." + table.qualifiedName().name().value();
    }

    private static String columnPath(Table table, Column column) {
        return tablePath(table) + ".columns." + column.name().value();
    }
}
