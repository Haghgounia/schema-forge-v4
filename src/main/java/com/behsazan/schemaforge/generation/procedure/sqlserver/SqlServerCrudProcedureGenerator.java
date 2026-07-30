package com.behsazan.schemaforge.generation.procedure.sqlserver;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerTypeMapper;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Generates five SQL Server CRUD stored procedures from live SQL Server metadata. */
public final class SqlServerCrudProcedureGenerator {
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
    private static final Pattern SEQUENCE_DEFAULT = Pattern.compile(
            "(?is).*\\bNEXT\\s+VALUE\\s+FOR\\b.*");
    private static final Pattern GUID_DEFAULT = Pattern.compile(
            "(?is).*\\bNEW(?:SEQUENTIAL)?ID\\s*\\(\\s*\\).*");
    private static final Pattern CONSTANT_DEFAULT = Pattern.compile(
            "(?is)(?:NULL|[-+]?\\d+(?:\\.\\d+)?|0x[0-9A-F]+|N?'(?:''|[^'])*')");

    private final SqlServerTypeMapper typeMapper;
    private final SqlServerCrudNamingStrategy naming;

    public SqlServerCrudProcedureGenerator() {
        this(new SqlServerTypeMapper(NumericMappingStrategy.SAFE), new SqlServerCrudNamingStrategy());
    }

    SqlServerCrudProcedureGenerator(SqlServerTypeMapper typeMapper, SqlServerCrudNamingStrategy naming) {
        this.typeMapper = Objects.requireNonNull(typeMapper, "typeMapper must not be null");
        this.naming = Objects.requireNonNull(naming, "naming must not be null");
    }

    public String generate(Table table) {
        return generate(table, SqlServerCrudGenerationOptions.defaults());
    }

    public String generate(Table table, SqlServerCrudGenerationOptions options) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Model model = buildModel(table, options);
        StringBuilder sql = new StringBuilder(32768);
        renderHeader(sql, model);
        renderCreate(sql, model);
        renderUpdate(sql, model);
        renderDelete(sql, model);
        renderGetById(sql, model);
        renderSearch(sql, model);
        renderGrantsAndSummary(sql, model);
        return sql.toString();
    }

    private Model buildModel(Table table, SqlServerCrudGenerationOptions options) {
        String schema = table.qualifiedName().schemaName()
                .map(Identifier::normalized)
                .orElseThrow(() -> new IllegalArgumentException(
                        "SQL Server CRUD generation requires a schema-qualified table"));
        String tableName = table.qualifiedName().name().normalized();
        var primaryKey = table.primaryKey().orElseThrow(() -> new IllegalArgumentException(
                "SQL Server CRUD generation requires a primary key: " + schema + "." + tableName));

        Map<String, Column> columnsByName = new LinkedHashMap<>();
        table.columns().stream()
                .sorted(Comparator.comparing(column ->
                        column.ordinalPosition() == null ? Integer.MAX_VALUE : column.ordinalPosition()))
                .forEach(column -> columnsByName.put(column.name().normalized(), column));

        List<Column> primaryKeyColumns = primaryKey.columns().stream()
                .map(identifier -> requireColumn(columnsByName, identifier.normalized(), "primary key"))
                .toList();
        List<Column> generatedKeyColumns = primaryKeyColumns.stream()
                .filter(SqlServerCrudProcedureGenerator::databaseGeneratedKey)
                .toList();
        Set<String> generatedKeyNames = generatedKeyColumns.stream()
                .map(column -> column.name().normalized())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        Set<String> primaryKeyNames = primaryKeyColumns.stream()
                .map(column -> column.name().normalized())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        Column createActorColumn = firstMatching(columnsByName.values(), CREATED_USER_COLUMNS);
        if (createActorColumn == null) {
            createActorColumn = firstMatching(columnsByName.values(), MODIFIED_USER_COLUMNS);
        }
        Column updateActorColumn = firstMatching(columnsByName.values(), MODIFIED_USER_COLUMNS);

        List<Column> insertParameterColumns = columnsByName.values().stream()
                .filter(column -> !column.generated())
                .filter(column -> !rowVersion(column))
                .filter(column -> !generatedKeyNames.contains(column.name().normalized()))
                .filter(column -> !auditTime(column))
                .filter(column -> !auditUser(column))
                .toList();

        List<Column> updateParameterColumns = columnsByName.values().stream()
                .filter(column -> !primaryKeyNames.contains(column.name().normalized()))
                .filter(column -> !column.generated())
                .filter(column -> !rowVersion(column))
                .filter(column -> !auditTime(column))
                .filter(column -> !auditUser(column))
                .toList();

        boolean hasModifiedAudit = columnsByName.values().stream().anyMatch(column ->
                MODIFIED_TIME_COLUMNS.contains(column.name().normalized())
                        || MODIFIED_USER_COLUMNS.contains(column.name().normalized()));
        if (updateParameterColumns.isEmpty() && !hasModifiedAudit) {
            throw new IllegalArgumentException(
                    "SQL Server CRUD generation found no updatable columns: " + schema + "." + tableName);
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
                List.copyOf(columnsByName.values()),
                primaryKeyColumns,
                generatedKeyColumns,
                insertParameterColumns,
                updateParameterColumns,
                searchColumns,
                createActorColumn,
                updateActorColumn,
                naming.createProcedure(tableName),
                naming.updateProcedure(tableName),
                naming.deleteProcedure(tableName),
                naming.getByIdProcedure(tableName),
                naming.searchProcedure(tableName),
                options);
    }

    private void renderHeader(StringBuilder sql, Model model) {
        line(sql, "-- ==============================================================");
        line(sql, "-- SchemaForge SQL Server Metadata CRUD Procedures");
        line(sql, "-- Source  : SQL Server sys.* catalog metadata");
        line(sql, "-- Table   : " + model.schema() + "." + model.tableName());
        line(sql, "-- Procedures are idempotent through CREATE OR ALTER.");
        line(sql, "-- Transaction ownership remains with the caller.");
        line(sql, "-- Execute this script with a SQL Server client that recognizes GO batches.");
        line(sql, "-- ==============================================================");
        blank(sql);
    }

    private void renderCreate(StringBuilder sql, Model model) {
        List<Parameter> parameters = createParameters(model);
        renderProcedureStart(sql, model, model.createProcedure(), parameters);
        line(sql, "    SET NOCOUNT ON;");
        line(sql, "    SET XACT_ABORT ON;");
        blank(sql);
        line(sql, "    BEGIN TRY");

        List<String> insertColumns = new ArrayList<>();
        List<String> insertValues = new ArrayList<>();
        Set<String> generatedKeys = model.generatedKeyColumns().stream()
                .map(column -> column.name().normalized())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        for (Column column : model.columns()) {
            String name = column.name().normalized();
            if (column.generated() || rowVersion(column) || generatedKeys.contains(name)) {
                continue;
            }
            insertColumns.add(naming.quote(name));
            if (CREATED_TIME_COLUMNS.contains(name) || MODIFIED_TIME_COLUMNS.contains(name)) {
                insertValues.add("SYSDATETIME()");
            } else if (CREATED_USER_COLUMNS.contains(name) || MODIFIED_USER_COLUMNS.contains(name)) {
                insertValues.add(model.createActorColumn() == null
                        ? "ORIGINAL_LOGIN()"
                        : naming.inputParameter(model.createActorColumn().name().normalized()));
            } else {
                insertValues.add(naming.inputParameter(name));
            }
        }

        if (!model.generatedKeyColumns().isEmpty()) {
            line(sql, "        DECLARE @GENERATED_KEYS TABLE");
            line(sql, "        (");
            for (int i = 0; i < model.generatedKeyColumns().size(); i++) {
                Column column = model.generatedKeyColumns().get(i);
                line(sql, "            " + naming.quote(column.name().normalized()) + " "
                        + typeMapper.map(column.dataType())
                        + (i + 1 < model.generatedKeyColumns().size() ? "," : ""));
            }
            line(sql, "        );");
            blank(sql);
        }

        if (insertColumns.isEmpty()) {
            line(sql, "        INSERT INTO " + model.qualifiedTable());
            renderOutputClause(sql, model, 8);
            line(sql, "        DEFAULT VALUES;");
        } else {
            line(sql, "        INSERT INTO " + model.qualifiedTable());
            renderList(sql, insertColumns, 8, false);
            renderOutputClause(sql, model, 8);
            line(sql, "        VALUES");
            renderList(sql, insertValues, 8, true);
        }

        if (!model.generatedKeyColumns().isEmpty()) {
            blank(sql);
            line(sql, "        SELECT TOP (1)");
            for (int i = 0; i < model.generatedKeyColumns().size(); i++) {
                Column column = model.generatedKeyColumns().get(i);
                line(sql, "            " + naming.outputParameter(column.name().normalized())
                        + " = " + naming.quote(column.name().normalized())
                        + (i + 1 < model.generatedKeyColumns().size() ? "," : ""));
            }
            line(sql, "          FROM @GENERATED_KEYS;");
        }

        line(sql, "    END TRY");
        line(sql, "    BEGIN CATCH");
        line(sql, "        IF ERROR_NUMBER() IN (2601, 2627)");
        line(sql, "            THROW 50002, 'Duplicate unique value for "
                + model.schema() + "." + model.tableName() + "', 1;");
        line(sql, "        THROW;");
        line(sql, "    END CATCH;");
        renderProcedureEnd(sql);
    }

    private void renderUpdate(StringBuilder sql, Model model) {
        renderProcedureStart(sql, model, model.updateProcedure(), updateParameters(model));
        line(sql, "    SET NOCOUNT ON;");
        line(sql, "    SET XACT_ABORT ON;");
        blank(sql);
        line(sql, "    BEGIN TRY");
        line(sql, "        UPDATE T");
        List<String> assignments = new ArrayList<>();
        for (Column column : model.updateParameterColumns()) {
            assignments.add(naming.quote(column.name().normalized()) + " = "
                    + naming.inputParameter(column.name().normalized()));
        }
        for (Column column : model.columns()) {
            String name = column.name().normalized();
            if (MODIFIED_TIME_COLUMNS.contains(name)) {
                assignments.add(naming.quote(name) + " = SYSDATETIME()");
            } else if (MODIFIED_USER_COLUMNS.contains(name)) {
                assignments.add(naming.quote(name) + " = " + (model.updateActorColumn() == null
                        ? "ORIGINAL_LOGIN()"
                        : naming.inputParameter(model.updateActorColumn().name().normalized())));
            }
        }
        for (int i = 0; i < assignments.size(); i++) {
            line(sql, (i == 0 ? "           SET " : "               ")
                    + assignments.get(i) + (i + 1 < assignments.size() ? "," : ""));
        }
        line(sql, "          FROM " + model.qualifiedTable() + " T");
        renderWhere(sql, model.primaryKeyColumns(), 9, true);
        blank(sql);
        line(sql, "        IF @@ROWCOUNT = 0");
        line(sql, "            THROW 50001, 'Row not found for "
                + model.schema() + "." + model.tableName() + " UPDATE', 1;");
        line(sql, "    END TRY");
        line(sql, "    BEGIN CATCH");
        line(sql, "        IF ERROR_NUMBER() IN (2601, 2627)");
        line(sql, "            THROW 50002, 'Duplicate unique value for "
                + model.schema() + "." + model.tableName() + "', 1;");
        line(sql, "        THROW;");
        line(sql, "    END CATCH;");
        renderProcedureEnd(sql);
    }

    private void renderDelete(StringBuilder sql, Model model) {
        renderProcedureStart(sql, model, model.deleteProcedure(), keyParameters(model));
        line(sql, "    SET NOCOUNT ON;");
        line(sql, "    SET XACT_ABORT ON;");
        blank(sql);
        line(sql, "    BEGIN TRY");
        line(sql, "        DELETE T");
        line(sql, "          FROM " + model.qualifiedTable() + " T");
        renderWhere(sql, model.primaryKeyColumns(), 9, true);
        blank(sql);
        line(sql, "        IF @@ROWCOUNT = 0");
        line(sql, "            THROW 50001, 'Row not found for "
                + model.schema() + "." + model.tableName() + " DELETE', 1;");
        line(sql, "    END TRY");
        line(sql, "    BEGIN CATCH");
        line(sql, "        IF ERROR_NUMBER() = 547");
        line(sql, "            THROW 50004, 'Cannot delete "
                + model.schema() + "." + model.tableName() + ": dependent records exist', 1;");
        line(sql, "        THROW;");
        line(sql, "    END CATCH;");
        renderProcedureEnd(sql);
    }

    private void renderGetById(StringBuilder sql, Model model) {
        renderProcedureStart(sql, model, model.getByIdProcedure(), keyParameters(model));
        line(sql, "    SET NOCOUNT ON;");
        blank(sql);
        renderSelect(sql, model, 4);
        line(sql, "      FROM " + model.qualifiedTable() + " T");
        renderWhere(sql, model.primaryKeyColumns(), 5, true);
        renderProcedureEnd(sql);
    }

    private void renderSearch(StringBuilder sql, Model model) {
        renderProcedureStart(sql, model, model.searchProcedure(), searchParameters(model));
        line(sql, "    SET NOCOUNT ON;");
        blank(sql);
        line(sql, "    IF @P_OFFSET < 0");
        line(sql, "        THROW 50003, '@P_OFFSET cannot be negative', 1;");
        blank(sql);
        line(sql, "    IF @P_LIMIT < 1 OR @P_LIMIT > " + model.options().maximumPageSize());
        line(sql, "        THROW 50003, '@P_LIMIT must be between 1 and "
                + model.options().maximumPageSize() + "', 1;");
        blank(sql);
        renderSelect(sql, model, 4);
        line(sql, "      FROM " + model.qualifiedTable() + " T");
        for (int i = 0; i < model.searchColumns().size(); i++) {
            Column column = model.searchColumns().get(i);
            String parameter = naming.inputParameter(column.name().normalized());
            line(sql, (i == 0 ? "     WHERE " : "       AND ")
                    + "(" + parameter + " IS NULL OR T."
                    + naming.quote(column.name().normalized()) + " = " + parameter + ")");
        }
        line(sql, "     ORDER BY " + model.primaryKeyColumns().stream()
                .map(column -> "T." + naming.quote(column.name().normalized()))
                .reduce((left, right) -> left + ", " + right).orElse("(SELECT 1)"));
        line(sql, "     OFFSET @P_OFFSET ROWS");
        line(sql, "     FETCH NEXT @P_LIMIT ROWS ONLY;");
        renderProcedureEnd(sql);
    }

    private void renderGrantsAndSummary(StringBuilder sql, Model model) {
        List<String> procedures = List.of(
                model.createProcedure(), model.updateProcedure(), model.deleteProcedure(),
                model.getByIdProcedure(), model.searchProcedure());
        for (String grantee : model.options().executeGrantees()) {
            for (String procedure : procedures) {
                line(sql, "GRANT EXECUTE ON OBJECT::" + naming.qualify(model.schema(), procedure)
                        + " TO " + naming.quote(grantee) + ";");
            }
        }
        if (model.options().executeGrantees().isEmpty()) {
            line(sql, "-- Grant EXECUTE only to approved application roles.");
        }
        blank(sql);
        line(sql, "/*");
        line(sql, "SchemaForge SQL Server CRUD Summary");
        line(sql, "Table              : " + model.schema() + "." + model.tableName());
        line(sql, "Create Procedure   : " + model.schema() + "." + model.createProcedure());
        line(sql, "Update Procedure   : " + model.schema() + "." + model.updateProcedure());
        line(sql, "Delete Procedure   : " + model.schema() + "." + model.deleteProcedure());
        line(sql, "Get Procedure      : " + model.schema() + "." + model.getByIdProcedure());
        line(sql, "Search Procedure   : " + model.schema() + "." + model.searchProcedure());
        line(sql, "Primary Key Columns: " + joinNames(model.primaryKeyColumns()));
        line(sql, "Generated PK       : " + (model.generatedKeyColumns().isEmpty()
                ? "NONE" : joinNames(model.generatedKeyColumns())));
        line(sql, "Search Filters     : " + joinNames(model.searchColumns()));
        line(sql, "Transaction Control: CALLER");
        line(sql, "*/");
    }

    private List<Parameter> createParameters(Model model) {
        List<Parameter> required = new ArrayList<>();
        List<Parameter> optional = new ArrayList<>();
        for (Column column : model.insertParameterColumns()) {
            String defaultExpression = parameterDefault(column);
            Parameter parameter = columnParameter(column, false, defaultExpression);
            (defaultExpression == null ? required : optional).add(parameter);
        }
        if (model.createActorColumn() != null) {
            required.add(columnParameter(model.createActorColumn(), false, null));
        }
        for (Column column : model.generatedKeyColumns()) {
            required.add(columnParameter(column, true, null));
        }
        required.addAll(optional);
        return required;
    }

    private List<Parameter> updateParameters(Model model) {
        List<Parameter> parameters = new ArrayList<>(keyParameters(model));
        for (Column column : model.updateParameterColumns()) {
            parameters.add(columnParameter(column, false, null));
        }
        if (model.updateActorColumn() != null) {
            parameters.add(columnParameter(model.updateActorColumn(), false, null));
        }
        return parameters;
    }

    private List<Parameter> keyParameters(Model model) {
        return model.primaryKeyColumns().stream()
                .map(column -> columnParameter(column, false, null))
                .toList();
    }

    private List<Parameter> searchParameters(Model model) {
        List<Parameter> parameters = new ArrayList<>();
        for (Column column : model.searchColumns()) {
            parameters.add(columnParameter(column, false, "NULL"));
        }
        parameters.add(new Parameter("@P_OFFSET", "INT", false, "0"));
        parameters.add(new Parameter("@P_LIMIT", "INT", false, "100"));
        return parameters;
    }

    private Parameter columnParameter(Column column, boolean output, String defaultExpression) {
        String name = output
                ? naming.outputParameter(column.name().normalized())
                : naming.inputParameter(column.name().normalized());
        return new Parameter(name, typeMapper.map(column.dataType()), output, defaultExpression);
    }

    private void renderProcedureStart(StringBuilder sql, Model model, String procedure,
                                      List<Parameter> parameters) {
        line(sql, "CREATE OR ALTER PROCEDURE " + naming.qualify(model.schema(), procedure));
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            StringBuilder declaration = new StringBuilder("    ")
                    .append(parameter.name()).append(' ')
                    .append(parameter.type());
            if (parameter.defaultExpression() != null) {
                declaration.append(" = ").append(parameter.defaultExpression());
            }
            if (parameter.output()) {
                declaration.append(" OUTPUT");
            }
            if (i + 1 < parameters.size()) {
                declaration.append(',');
            }
            line(sql, declaration.toString());
        }
        line(sql, "AS");
        line(sql, "BEGIN");
    }

    private static void renderProcedureEnd(StringBuilder sql) {
        line(sql, "END;");
        line(sql, "GO");
        blank(sql);
    }

    private void renderOutputClause(StringBuilder sql, Model model, int indent) {
        if (model.generatedKeyColumns().isEmpty()) {
            return;
        }
        String prefix = " ".repeat(indent);
        line(sql, prefix + "OUTPUT " + model.generatedKeyColumns().stream()
                .map(column -> "INSERTED." + naming.quote(column.name().normalized()))
                .reduce((left, right) -> left + ", " + right).orElseThrow());
        line(sql, prefix + "INTO @GENERATED_KEYS (" + model.generatedKeyColumns().stream()
                .map(column -> naming.quote(column.name().normalized()))
                .reduce((left, right) -> left + ", " + right).orElseThrow() + ")");
    }

    private void renderSelect(StringBuilder sql, Model model, int indent) {
        String prefix = " ".repeat(indent);
        line(sql, prefix + "SELECT");
        for (int i = 0; i < model.columns().size(); i++) {
            Column column = model.columns().get(i);
            line(sql, prefix + "    T." + naming.quote(column.name().normalized())
                    + (i + 1 < model.columns().size() ? "," : ""));
        }
    }

    private void renderWhere(StringBuilder sql, List<Column> columns, int indent, boolean semicolon) {
        String prefix = " ".repeat(indent);
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            line(sql, prefix + (i == 0 ? "WHERE " : "  AND ")
                    + "T." + naming.quote(column.name().normalized())
                    + " = " + naming.inputParameter(column.name().normalized())
                    + (semicolon && i + 1 == columns.size() ? ";" : ""));
        }
    }

    private static void renderList(StringBuilder sql, List<String> values, int indent, boolean semicolon) {
        String prefix = " ".repeat(indent);
        line(sql, prefix + "(");
        for (int i = 0; i < values.size(); i++) {
            line(sql, prefix + "    " + values.get(i) + (i + 1 < values.size() ? "," : ""));
        }
        line(sql, prefix + ")" + (semicolon ? ";" : ""));
    }

    private static Column requireColumn(Map<String, Column> columns, String name, String owner) {
        Column column = columns.get(name);
        if (column == null) {
            throw new IllegalArgumentException(owner + " references missing column: " + name);
        }
        return column;
    }

    private static Column firstMatching(Iterable<Column> columns, Set<String> names) {
        for (Column column : columns) {
            if (names.contains(column.name().normalized())) {
                return column;
            }
        }
        return null;
    }

    private static boolean databaseGeneratedKey(Column column) {
        if (column.identity() || column.generated() || rowVersion(column)) {
            return true;
        }
        String expression = column.defaultValue().expression();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        return SEQUENCE_DEFAULT.matcher(expression).matches()
                || GUID_DEFAULT.matcher(expression).matches();
    }

    private static boolean rowVersion(Column column) {
        String name = column.dataType().name().normalized();
        return name.equals("ROWVERSION")
                || name.equals("SQLSERVER_TIMESTAMP")
                || name.equals("TIMESTAMP");
    }

    private static boolean auditTime(Column column) {
        String name = column.name().normalized();
        return CREATED_TIME_COLUMNS.contains(name) || MODIFIED_TIME_COLUMNS.contains(name);
    }

    private static boolean auditUser(Column column) {
        String name = column.name().normalized();
        return CREATED_USER_COLUMNS.contains(name) || MODIFIED_USER_COLUMNS.contains(name);
    }

    private static String parameterDefault(Column column) {
        String defaultValue = normalizeConstantDefault(column.defaultValue().expression());
        if (defaultValue != null) {
            return defaultValue;
        }
        return column.nullable() ? "NULL" : null;
    }

    private static String normalizeConstantDefault(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String normalized = expression.trim();
        boolean changed;
        do {
            changed = normalized.length() >= 2
                    && normalized.charAt(0) == '('
                    && normalized.charAt(normalized.length() - 1) == ')'
                    && wrapsEntireExpression(normalized);
            if (changed) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        } while (changed);
        return CONSTANT_DEFAULT.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean wrapsEntireExpression(String value) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\'' && (i + 1 >= value.length() || value.charAt(i + 1) != '\'')) {
                inString = !inString;
            } else if (current == '\'' && inString && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                i++;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
                if (depth == 0 && i + 1 < value.length()) {
                    return false;
                }
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0 && !inString;
    }

    private static String joinNames(List<Column> columns) {
        return columns.stream().map(column -> column.name().normalized())
                .reduce((left, right) -> left + ", " + right).orElse("NONE");
    }

    private static void line(StringBuilder sql, String value) {
        sql.append(value).append(System.lineSeparator());
    }

    private static void blank(StringBuilder sql) {
        sql.append(System.lineSeparator());
    }

    private record Parameter(String name, String type, boolean output, String defaultExpression) { }

    private record Model(
            String schema,
            String tableName,
            List<Column> columns,
            List<Column> primaryKeyColumns,
            List<Column> generatedKeyColumns,
            List<Column> insertParameterColumns,
            List<Column> updateParameterColumns,
            List<Column> searchColumns,
            Column createActorColumn,
            Column updateActorColumn,
            String createProcedure,
            String updateProcedure,
            String deleteProcedure,
            String getByIdProcedure,
            String searchProcedure,
            SqlServerCrudGenerationOptions options) {
        String qualifiedTable() {
            return "[" + schema.replace("]", "]]") + "].[" + tableName.replace("]", "]]") + "]";
        }
    }
}
