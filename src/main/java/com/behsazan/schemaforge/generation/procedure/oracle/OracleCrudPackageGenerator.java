package com.behsazan.schemaforge.generation.procedure.oracle;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Generates one Oracle CRUD package from live Oracle table metadata. */
public final class OracleCrudPackageGenerator {
    private static final int ORACLE_COMPATIBLE_IDENTIFIER_LIMIT = 30;

    private static final Set<String> CREATED_TIME_COLUMNS = Set.of(
            "CREATED_DATE", "CREATED_AT", "CREATED_TIMESTAMP", "CREATE_DATE", "CREATE_TIMESTAMP");
    private static final Set<String> MODIFIED_TIME_COLUMNS = Set.of(
            "LAST_MODIFIED_DATE", "LAST_MODIFIED_AT", "LAST_MODIFIED_TIMESTAMP",
            "UPDATED_DATE", "UPDATED_AT", "UPDATED_TIMESTAMP", "UPDATE_DATE", "UPDATE_TIMESTAMP",
            "MODIFIED_DATE", "MODIFIED_AT", "LAST_UPDATED_DATE", "LAST_UPDATE_DATE");
    private static final Set<String> CREATED_USER_COLUMNS = Set.of(
            "CREATED_BY", "CREATED_USER", "CREATED_USER_ID", "CREATE_USER", "CREATE_USER_ID");
    private static final Set<String> MODIFIED_USER_COLUMNS = Set.of(
            "LAST_MODIFIED_BY", "LAST_MODIFIED_USER", "LAST_MODIFIED_USER_ID",
            "UPDATED_BY", "UPDATED_USER", "UPDATED_USER_ID", "UPDATE_USER", "UPDATE_USER_ID",
            "MODIFIED_BY", "LAST_UPDATED_BY");
    private static final Set<String> STATUS_SEARCH_COLUMNS = Set.of(
            "IS_ACTIVE", "STATUS", "STATUS_CODE", "STATE", "STATE_CODE");

    public String generate(Table table) {
        return generate(table, OracleCrudGenerationOptions.defaults());
    }

    public String generate(Table table, OracleCrudGenerationOptions options) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Model model = buildModel(table, options);
        StringBuilder sql = new StringBuilder(32768);
        renderHeader(sql, model);
        renderSpecification(sql, model);
        renderBody(sql, model);
        renderValidationAndGrants(sql, model);
        return sql.toString();
    }

    private static Model buildModel(Table table, OracleCrudGenerationOptions options) {
        String schema = table.qualifiedName().schemaName()
                .map(Identifier::normalized)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Oracle CRUD generation requires a schema-qualified table"));
        String tableName = table.qualifiedName().name().normalized();
        var primaryKey = table.primaryKey().orElseThrow(() -> new IllegalArgumentException(
                "Oracle CRUD generation requires a primary key: " + schema + "." + tableName));

        Map<String, Column> columnsByName = new LinkedHashMap<>();
        table.columns().stream()
                .sorted(Comparator.comparing(column ->
                        column.ordinalPosition() == null ? Integer.MAX_VALUE : column.ordinalPosition()))
                .forEach(column -> columnsByName.put(column.name().normalized(), column));

        List<Column> primaryKeyColumns = primaryKey.columns().stream()
                .map(identifier -> requireColumn(columnsByName, identifier.normalized(), "primary key"))
                .toList();
        List<Column> generatedKeyColumns = primaryKeyColumns.stream()
                .filter(OracleCrudPackageGenerator::databaseGenerated)
                .toList();

        List<Column> regularInsertColumns = columnsByName.values().stream()
                .filter(column -> !column.generated())
                .filter(column -> !databaseGenerated(column))
                .filter(column -> !auditTime(column))
                .filter(column -> !auditUser(column))
                .toList();

        Column createActorColumn = firstMatching(columnsByName.values(), CREATED_USER_COLUMNS);
        if (createActorColumn == null) {
            createActorColumn = firstMatching(columnsByName.values(), MODIFIED_USER_COLUMNS);
        }
        Column updateActorColumn = firstMatching(columnsByName.values(), MODIFIED_USER_COLUMNS);

        Set<String> pkNames = primaryKeyColumns.stream()
                .map(column -> column.name().normalized())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<Column> regularUpdateColumns = columnsByName.values().stream()
                .filter(column -> !pkNames.contains(column.name().normalized()))
                .filter(column -> !column.generated())
                .filter(column -> !databaseGenerated(column))
                .filter(column -> !auditTime(column))
                .filter(column -> !auditUser(column))
                .toList();

        boolean hasModifiedAudit = columnsByName.values().stream().anyMatch(column ->
                MODIFIED_TIME_COLUMNS.contains(column.name().normalized())
                        || MODIFIED_USER_COLUMNS.contains(column.name().normalized()));
        if (regularUpdateColumns.isEmpty() && !hasModifiedAudit) {
            throw new IllegalArgumentException(
                    "Oracle CRUD generation found no updatable columns: " + schema + "." + tableName);
        }

        LinkedHashSet<String> searchNames = new LinkedHashSet<>();
        primaryKeyColumns.forEach(column -> searchNames.add(column.name().normalized()));
        table.uniqueKeys().forEach(uniqueKey -> uniqueKey.columns()
                .forEach(identifier -> searchNames.add(identifier.normalized())));
        columnsByName.values().stream()
                .map(column -> column.name().normalized())
                .filter(STATUS_SEARCH_COLUMNS::contains)
                .forEach(searchNames::add);
        List<Column> searchColumns = searchNames.stream()
                .map(name -> requireColumn(columnsByName, name, "search filter"))
                .toList();

        return new Model(
                schema,
                tableName,
                safeIdentifier("PKG_" + tableName),
                List.copyOf(columnsByName.values()),
                primaryKeyColumns,
                generatedKeyColumns,
                regularInsertColumns,
                regularUpdateColumns,
                searchColumns,
                createActorColumn,
                updateActorColumn,
                options);
    }

    private static void renderHeader(StringBuilder sql, Model model) {
        line(sql, "PROMPT ==============================================================");
        line(sql, "PROMPT SchemaForge Oracle Metadata CRUD Package");
        line(sql, "PROMPT Source  : Oracle Data Dictionary");
        line(sql, "PROMPT Table   : " + model.qualifiedTable());
        line(sql, "PROMPT Package : " + model.qualifiedPackage());
        line(sql, "PROMPT Transaction ownership remains with the caller.");
        line(sql, "PROMPT ==============================================================");
        line(sql, "ALTER SESSION SET PLSQL_WARNINGS = 'ENABLE:ALL';");
        blank(sql);
    }

    private static void renderSpecification(StringBuilder sql, Model model) {
        line(sql, "CREATE OR REPLACE PACKAGE " + model.qualifiedPackage());
        line(sql, "AUTHID DEFINER");
        line(sql, "AS");
        renderProcedureDeclaration(sql, "CREATE_ROW", createParameters(model, true));
        renderProcedureDeclaration(sql, "UPDATE_ROW", updateParameters(model));
        renderProcedureDeclaration(sql, "DELETE_ROW", keyParameters(model));

        List<Parameter> getParameters = new ArrayList<>(keyParameters(model));
        getParameters.add(new Parameter("O_RESULT", "OUT", "SYS_REFCURSOR", null));
        renderProcedureDeclaration(sql, "GET_BY_ID", getParameters);
        renderProcedureDeclaration(sql, "SEARCH", searchParameters(model, true));

        line(sql, "END " + model.packageName() + ";");
        line(sql, "/");
        line(sql, "SHOW ERRORS PACKAGE " + model.qualifiedPackage() + ";");
        blank(sql);
    }

    private static void renderBody(StringBuilder sql, Model model) {
        line(sql, "CREATE OR REPLACE PACKAGE BODY " + model.qualifiedPackage());
        line(sql, "AS");
        line(sql, "    C_ERR_NOT_FOUND      CONSTANT PLS_INTEGER := -20001;");
        line(sql, "    C_ERR_DUPLICATE      CONSTANT PLS_INTEGER := -20002;");
        line(sql, "    C_ERR_INVALID_PAGE   CONSTANT PLS_INTEGER := -20003;");
        line(sql, "    C_ERR_CHILD_RECORD   CONSTANT PLS_INTEGER := -20004;");
        blank(sql);
        line(sql, "    E_CHILD_RECORD_FOUND EXCEPTION;");
        line(sql, "    PRAGMA EXCEPTION_INIT(E_CHILD_RECORD_FOUND, -2292);");
        blank(sql);

        renderCreate(sql, model);
        renderUpdate(sql, model);
        renderDelete(sql, model);
        renderGetById(sql, model);
        renderSearch(sql, model);

        line(sql, "END " + model.packageName() + ";");
        line(sql, "/");
        line(sql, "SHOW ERRORS PACKAGE BODY " + model.qualifiedPackage() + ";");
        blank(sql);
    }

    private static void renderCreate(StringBuilder sql, Model model) {
        renderProcedureStart(sql, "CREATE_ROW", createParameters(model, false));
        line(sql, "    BEGIN");

        List<String> insertColumns = new ArrayList<>();
        List<String> insertValues = new ArrayList<>();
        for (Column column : model.regularInsertColumns()) {
            insertColumns.add(column.name().normalized());
            insertValues.add(parameterName("P", column));
        }
        for (Column column : model.columns()) {
            String name = column.name().normalized();
            if (CREATED_TIME_COLUMNS.contains(name) || MODIFIED_TIME_COLUMNS.contains(name)) {
                insertColumns.add(name);
                insertValues.add(auditTimeExpression(column));
            } else if (CREATED_USER_COLUMNS.contains(name) || MODIFIED_USER_COLUMNS.contains(name)) {
                insertColumns.add(name);
                insertValues.add(model.createActorColumn() == null
                        ? "SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')"
                        : parameterName("P", model.createActorColumn()));
            }
        }
        if (insertColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Oracle CRUD generation found no explicit INSERT columns: " + model.qualifiedTable());
        }

        line(sql, "        INSERT INTO " + model.qualifiedTable());
        renderParenthesizedList(sql, insertColumns, 8, false);
        line(sql, "        VALUES");
        renderParenthesizedList(sql, insertValues, 8, model.generatedKeyColumns().isEmpty());
        if (!model.generatedKeyColumns().isEmpty()) {
            line(sql, "        RETURNING " + joinColumnNames(model.generatedKeyColumns()));
            line(sql, "             INTO " + joinParameterNames("O", model.generatedKeyColumns()) + ";");
        }
        blank(sql);
        line(sql, "    EXCEPTION");
        line(sql, "        WHEN DUP_VAL_ON_INDEX THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(");
        line(sql, "                C_ERR_DUPLICATE,");
        line(sql, "                'Duplicate unique value for " + model.qualifiedTable() + "');");
        line(sql, "    END CREATE_ROW;");
        blank(sql);
    }

    private static void renderUpdate(StringBuilder sql, Model model) {
        renderProcedureStart(sql, "UPDATE_ROW", updateParameters(model));
        line(sql, "    BEGIN");
        line(sql, "        UPDATE " + model.qualifiedTable());
        List<String> assignments = updateAssignments(model);
        for (int i = 0; i < assignments.size(); i++) {
            line(sql, (i == 0 ? "           SET " : "               ")
                    + assignments.get(i) + (i + 1 < assignments.size() ? "," : ""));
        }
        renderWhere(sql, model.primaryKeyColumns(), 9, false, true);
        blank(sql);
        renderRowCountCheck(sql, model, "UPDATE_ROW");
        blank(sql);
        line(sql, "    EXCEPTION");
        line(sql, "        WHEN DUP_VAL_ON_INDEX THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(");
        line(sql, "                C_ERR_DUPLICATE,");
        line(sql, "                'Duplicate unique value for " + model.qualifiedTable() + "');");
        line(sql, "    END UPDATE_ROW;");
        blank(sql);
    }

    private static void renderDelete(StringBuilder sql, Model model) {
        renderProcedureStart(sql, "DELETE_ROW", keyParameters(model));
        line(sql, "    BEGIN");
        line(sql, "        DELETE FROM " + model.qualifiedTable());
        renderWhere(sql, model.primaryKeyColumns(), 9, false, true);
        blank(sql);
        renderRowCountCheck(sql, model, "DELETE_ROW");
        blank(sql);
        line(sql, "    EXCEPTION");
        line(sql, "        WHEN E_CHILD_RECORD_FOUND THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(");
        line(sql, "                C_ERR_CHILD_RECORD,");
        line(sql, "                'Cannot delete " + model.qualifiedTable() + ": dependent records exist');");
        line(sql, "    END DELETE_ROW;");
        blank(sql);
    }

    private static void renderGetById(StringBuilder sql, Model model) {
        List<Parameter> parameters = new ArrayList<>(keyParameters(model));
        parameters.add(new Parameter("O_RESULT", "OUT", "SYS_REFCURSOR", null));
        renderProcedureStart(sql, "GET_BY_ID", parameters);
        line(sql, "    BEGIN");
        line(sql, "        OPEN O_RESULT FOR");
        renderSelect(sql, model, 12);
        line(sql, "          FROM " + model.qualifiedTable() + " T");
        renderWhere(sql, model.primaryKeyColumns(), 9, true, true);
        line(sql, "    END GET_BY_ID;");
        blank(sql);
    }

    private static void renderSearch(StringBuilder sql, Model model) {
        renderProcedureStart(sql, "SEARCH", searchParameters(model, false));
        line(sql, "        L_OFFSET PLS_INTEGER := NVL(P_OFFSET, 0);");
        line(sql, "        L_LIMIT  PLS_INTEGER := NVL(P_LIMIT, 100);");
        line(sql, "    BEGIN");
        line(sql, "        IF L_OFFSET < 0 THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(C_ERR_INVALID_PAGE, 'P_OFFSET cannot be negative');");
        line(sql, "        END IF;");
        blank(sql);
        line(sql, "        IF L_LIMIT < 1 OR L_LIMIT > " + model.options().maximumPageSize() + " THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(");
        line(sql, "                C_ERR_INVALID_PAGE,");
        line(sql, "                'P_LIMIT must be between 1 and " + model.options().maximumPageSize() + "');");
        line(sql, "        END IF;");
        blank(sql);
        line(sql, "        OPEN O_RESULT FOR");
        renderSelect(sql, model, 12);
        line(sql, "          FROM " + model.qualifiedTable() + " T");
        for (int i = 0; i < model.searchColumns().size(); i++) {
            Column column = model.searchColumns().get(i);
            String parameter = parameterName("P", column);
            line(sql, (i == 0 ? "         WHERE " : "           AND ")
                    + "(" + parameter + " IS NULL OR T." + column.name().normalized()
                    + " = " + parameter + ")");
        }
        line(sql, "         ORDER BY " + model.primaryKeyColumns().stream()
                .map(column -> "T." + column.name().normalized())
                .reduce((left, right) -> left + ", " + right).orElse("1"));
        line(sql, "         OFFSET L_OFFSET ROWS");
        line(sql, "         FETCH NEXT L_LIMIT ROWS ONLY;");
        line(sql, "    END SEARCH;");
        blank(sql);
    }

    private static void renderValidationAndGrants(StringBuilder sql, Model model) {
        line(sql, "PROMPT ==============================================================");
        line(sql, "PROMPT SchemaForge Oracle CRUD Package Compilation Validation");
        line(sql, "PROMPT ==============================================================");
        line(sql, "SELECT NAME, TYPE, LINE, POSITION, TEXT");
        line(sql, "  FROM ALL_ERRORS");
        line(sql, " WHERE OWNER = '" + model.schema() + "'");
        line(sql, "   AND NAME = '" + model.packageName() + "'");
        line(sql, " ORDER BY TYPE, SEQUENCE;");
        blank(sql);
        for (String grantee : model.options().executeGrantees()) {
            line(sql, "GRANT EXECUTE ON " + model.qualifiedPackage() + " TO " + grantee + ";");
        }
        if (model.options().executeGrantees().isEmpty()) {
            line(sql, "-- Grant EXECUTE only to approved application roles.");
        }
        blank(sql);
        line(sql, "/*");
        line(sql, "SchemaForge Oracle CRUD Summary");
        line(sql, "Table              : " + model.qualifiedTable());
        line(sql, "Package            : " + model.qualifiedPackage());
        line(sql, "Primary Key Columns: " + joinColumnNames(model.primaryKeyColumns()));
        line(sql, "Generated PK       : " + (model.generatedKeyColumns().isEmpty()
                ? "NONE" : joinColumnNames(model.generatedKeyColumns())));
        line(sql, "Search Filters     : " + joinColumnNames(model.searchColumns()));
        line(sql, "Transaction Control: CALLER");
        line(sql, "*/");
    }

    private static List<Parameter> createParameters(Model model, boolean includeDefaults) {
        List<Parameter> required = new ArrayList<>();
        List<Parameter> optional = new ArrayList<>();
        for (Column column : model.regularInsertColumns()) {
            String declaredDefault = null;
            if (column.defaultValue().isPresent()) {
                declaredDefault = normalizeDefault(column.defaultValue().expression());
            } else if (column.nullable()) {
                declaredDefault = "NULL";
            }
            Parameter parameter = columnParameter(
                    model,
                    "P",
                    column,
                    "IN",
                    includeDefaults ? declaredDefault : null);
            (declaredDefault == null ? required : optional).add(parameter);
        }
        if (model.createActorColumn() != null) {
            required.add(columnParameter(model, "P", model.createActorColumn(), "IN", null));
        }
        for (Column generatedKey : model.generatedKeyColumns()) {
            required.add(columnParameter(model, "O", generatedKey, "OUT", null));
        }
        required.addAll(optional);
        return required;
    }

    private static List<Parameter> updateParameters(Model model) {
        List<Parameter> parameters = new ArrayList<>(keyParameters(model));
        model.regularUpdateColumns().forEach(column ->
                parameters.add(columnParameter(model, "P", column, "IN", null)));
        if (model.updateActorColumn() != null) {
            parameters.add(columnParameter(model, "P", model.updateActorColumn(), "IN", null));
        }
        return parameters;
    }

    private static List<Parameter> keyParameters(Model model) {
        return model.primaryKeyColumns().stream()
                .map(column -> columnParameter(model, "P", column, "IN", null))
                .toList();
    }

    private static List<Parameter> searchParameters(Model model, boolean includeDefaults) {
        List<Parameter> parameters = new ArrayList<>();
        parameters.add(new Parameter("O_RESULT", "OUT", "SYS_REFCURSOR", null));
        for (Column column : model.searchColumns()) {
            parameters.add(columnParameter(model, "P", column, "IN", includeDefaults ? "NULL" : null));
        }
        parameters.add(new Parameter("P_OFFSET", "IN", "PLS_INTEGER", includeDefaults ? "0" : null));
        parameters.add(new Parameter("P_LIMIT", "IN", "PLS_INTEGER", includeDefaults ? "100" : null));
        return parameters;
    }

    private static List<String> updateAssignments(Model model) {
        List<String> assignments = new ArrayList<>();
        for (Column column : model.regularUpdateColumns()) {
            assignments.add(column.name().normalized() + " = " + parameterName("P", column));
        }
        for (Column column : model.columns()) {
            String name = column.name().normalized();
            if (MODIFIED_TIME_COLUMNS.contains(name)) {
                assignments.add(name + " = " + auditTimeExpression(column));
            } else if (MODIFIED_USER_COLUMNS.contains(name)) {
                assignments.add(name + " = " + (model.updateActorColumn() == null
                        ? "SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')"
                        : parameterName("P", model.updateActorColumn())));
            }
        }
        return assignments;
    }

    private static void renderProcedureDeclaration(StringBuilder sql, String name, List<Parameter> parameters) {
        line(sql, "    PROCEDURE " + name);
        renderParameters(sql, parameters, 4, ");");
        blank(sql);
    }

    private static void renderProcedureStart(StringBuilder sql, String name, List<Parameter> parameters) {
        line(sql, "    PROCEDURE " + name);
        renderParameters(sql, parameters, 4, ")");
        line(sql, "    IS");
    }

    private static void renderParameters(StringBuilder sql, List<Parameter> parameters,
                                         int baseIndent, String closing) {
        String indent = " ".repeat(baseIndent);
        line(sql, indent + "(");
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            StringBuilder declaration = new StringBuilder(indent)
                    .append("    ")
                    .append(parameter.name()).append(' ')
                    .append(parameter.mode()).append(' ')
                    .append(parameter.type());
            if (parameter.defaultExpression() != null) {
                declaration.append(" DEFAULT ").append(parameter.defaultExpression());
            }
            if (i + 1 < parameters.size()) {
                declaration.append(',');
            }
            line(sql, declaration.toString());
        }
        line(sql, indent + closing);
    }

    private static void renderParenthesizedList(StringBuilder sql, List<String> values,
                                                 int indentSize, boolean semicolon) {
        String indent = " ".repeat(indentSize);
        line(sql, indent + "(");
        for (int i = 0; i < values.size(); i++) {
            line(sql, indent + "    " + values.get(i) + (i + 1 < values.size() ? "," : ""));
        }
        line(sql, indent + ")" + (semicolon ? ";" : ""));
    }

    private static void renderSelect(StringBuilder sql, Model model, int indentSize) {
        String indent = " ".repeat(indentSize);
        for (int i = 0; i < model.columns().size(); i++) {
            String prefix = i == 0 ? "SELECT " : "       ";
            String suffix = i + 1 < model.columns().size() ? "," : "";
            line(sql, indent + prefix + "T." + model.columns().get(i).name().normalized() + suffix);
        }
    }

    private static void renderWhere(StringBuilder sql, List<Column> keyColumns,
                                    int indentSize, boolean alias, boolean terminate) {
        String indent = " ".repeat(indentSize);
        for (int i = 0; i < keyColumns.size(); i++) {
            Column column = keyColumns.get(i);
            String suffix = terminate && i + 1 == keyColumns.size() ? ";" : "";
            line(sql, indent + (i == 0 ? "WHERE " : "  AND ")
                    + (alias ? "T." : "") + column.name().normalized()
                    + " = " + parameterName("P", column) + suffix);
        }
    }

    private static void renderRowCountCheck(StringBuilder sql, Model model, String operation) {
        line(sql, "        IF SQL%ROWCOUNT = 0 THEN");
        line(sql, "            RAISE_APPLICATION_ERROR(");
        line(sql, "                C_ERR_NOT_FOUND,");
        line(sql, "                'No row found for " + operation + " on " + model.qualifiedTable() + "');");
        line(sql, "        END IF;");
    }

    private static Parameter columnParameter(Model model, String prefix, Column column,
                                             String mode, String defaultExpression) {
        return new Parameter(
                parameterName(prefix, column),
                mode,
                model.qualifiedTable() + "." + column.name().normalized() + "%TYPE",
                defaultExpression);
    }

    private static String parameterName(String prefix, Column column) {
        return safeIdentifier(prefix + "_" + column.name().normalized());
    }

    private static String normalizeDefault(String expression) {
        return expression == null ? null : expression.trim().replaceAll("\\s+", " ");
    }

    private static boolean databaseGenerated(Column column) {
        if (column.identity()) {
            return true;
        }
        return column.defaultValue().isPresent()
                && column.defaultValue().expression().toUpperCase(Locale.ROOT).contains(".NEXTVAL");
    }

    private static String auditTimeExpression(Column column) {
        return column.dataType().name().normalized().equals("DATE") ? "SYSDATE" : "SYSTIMESTAMP";
    }

    private static boolean auditTime(Column column) {
        String name = column.name().normalized();
        return CREATED_TIME_COLUMNS.contains(name) || MODIFIED_TIME_COLUMNS.contains(name);
    }

    private static boolean auditUser(Column column) {
        String name = column.name().normalized();
        return CREATED_USER_COLUMNS.contains(name) || MODIFIED_USER_COLUMNS.contains(name);
    }

    private static Column firstMatching(Iterable<Column> columns, Set<String> names) {
        for (Column column : columns) {
            if (names.contains(column.name().normalized())) {
                return column;
            }
        }
        return null;
    }

    private static Column requireColumn(Map<String, Column> columns, String name, String owner) {
        Column column = columns.get(name);
        if (column == null) {
            throw new IllegalArgumentException(owner + " references missing column: " + name);
        }
        return column;
    }

    private static String joinColumnNames(List<Column> columns) {
        return columns.stream().map(column -> column.name().normalized())
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String joinParameterNames(String prefix, List<Column> columns) {
        return columns.stream().map(column -> parameterName(prefix, column))
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String safeIdentifier(String raw) {
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (normalized.length() <= ORACLE_COMPATIBLE_IDENTIFIER_LIMIT) {
            return normalized;
        }
        String hash = Integer.toUnsignedString(normalized.hashCode(), 16).toUpperCase(Locale.ROOT);
        hash = "0".repeat(Math.max(0, 8 - hash.length())) + hash;
        if (hash.length() > 8) {
            hash = hash.substring(hash.length() - 8);
        }
        int prefixLength = ORACLE_COMPATIBLE_IDENTIFIER_LIMIT - 1 - hash.length();
        return normalized.substring(0, prefixLength) + "_" + hash;
    }

    private static void line(StringBuilder sql, String value) {
        sql.append(value).append('\n');
    }

    private static void blank(StringBuilder sql) {
        sql.append('\n');
    }

    private record Parameter(String name, String mode, String type, String defaultExpression) { }

    private record Model(
            String schema,
            String tableName,
            String packageName,
            List<Column> columns,
            List<Column> primaryKeyColumns,
            List<Column> generatedKeyColumns,
            List<Column> regularInsertColumns,
            List<Column> regularUpdateColumns,
            List<Column> searchColumns,
            Column createActorColumn,
            Column updateActorColumn,
            OracleCrudGenerationOptions options) {

        String qualifiedTable() {
            return schema + "." + tableName;
        }

        String qualifiedPackage() {
            return schema + "." + packageName;
        }
    }
}
