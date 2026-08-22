package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Computes deterministic live-to-document differences used by ALTER/Migration generation. */
public final class SchemaDiffEngine {
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("^([A-Z0-9_ ]+)\\((\\d+)(?:,(\\d+))?(?: (?:CHAR|BYTE|CHARACTERS?))?\\)(.*)$");

    public TableMigrationPlan diff(DatabasePlatform platform, Table liveTable, Table desiredTable) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(liveTable, "liveTable must not be null");
        Objects.requireNonNull(desiredTable, "desiredTable must not be null");
        requireSameTable(liveTable, desiredTable);

        Dialect dialect = DialectFactory.create(platform);
        List<ColumnChange> columnChanges = diffColumns(platform, dialect, liveTable, desiredTable);
        List<TableObjectChange> objectChanges = diffObjects(platform, dialect, liveTable, desiredTable);
        return new TableMigrationPlan(platform, liveTable, desiredTable, columnChanges, objectChanges);
    }

    private List<ColumnChange> diffColumns(
            DatabasePlatform platform, Dialect dialect, Table liveTable, Table desiredTable) {
        Map<String, Column> liveByName = byName(liveTable.columns());
        Map<String, Column> desiredByName = byName(desiredTable.columns());
        List<ColumnChange> changes = new ArrayList<>();

        List<Column> desiredColumns = new ArrayList<>(desiredTable.columns());
        desiredColumns.sort(columnOrder());
        for (Column desired : desiredColumns) {
            Column live = liveByName.get(desired.name().normalized());
            if (live == null) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.ADD_COLUMN, desired.name(), null, desired,
                        addRisk(desired), addRationale(desired)));
                continue;
            }

            String liveType = liveType(platform, dialect, liveTable, live);
            String desiredType = desiredType(dialect, desiredTable, desired);
            if (!normalizeType(liveType).equals(normalizeType(desiredType))) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.ALTER_TYPE, desired.name(), live, desired,
                        typeRisk(liveType, desiredType),
                        "datatype changes from " + liveType + " to " + desiredType));
            }

            if (live.nullable() != desired.nullable()) {
                MigrationRisk risk = desired.nullable() ? MigrationRisk.SAFE : MigrationRisk.REVIEW;
                String rationale = desired.nullable()
                        ? "column becomes nullable"
                        : "column becomes NOT NULL; verify existing rows contain no NULL values";
                changes.add(new ColumnChange(
                        ColumnChangeKind.ALTER_NULLABILITY, desired.name(), live, desired, risk, rationale));
            }

            if (!sameDefault(platform, dialect, live, desired)) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.ALTER_DEFAULT, desired.name(), live, desired, MigrationRisk.REVIEW,
                        "default expression changes from " + defaultLabel(live) + " to " + defaultLabel(desired)));
            }

            if (live.identity() != desired.identity()) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.ALTER_IDENTITY, desired.name(), live, desired, MigrationRisk.REVIEW,
                        "identity property changes; automatic identity transition requires operational review"));
            }

            if (!Objects.equals(normalizeExpression(live.generatedExpression()),
                    normalizeExpression(desired.generatedExpression()))) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.ALTER_GENERATED_EXPRESSION, desired.name(), live, desired, MigrationRisk.REVIEW,
                        "generated expression changes; automatic expression transition requires operational review"));
            }
        }

        List<Column> liveColumns = new ArrayList<>(liveTable.columns());
        liveColumns.sort(columnOrder());
        for (Column live : liveColumns) {
            if (!desiredByName.containsKey(live.name().normalized())) {
                changes.add(new ColumnChange(
                        ColumnChangeKind.DROP_COLUMN, live.name(), live, null, MigrationRisk.DESTRUCTIVE,
                        "column is present in live metadata but absent from the desired document; rename is never inferred"));
            }
        }
        return List.copyOf(changes);
    }

    private List<TableObjectChange> diffObjects(
            DatabasePlatform platform, Dialect dialect, Table live, Table desired) {
        List<TableObjectChange> changes = new ArrayList<>();
        diffPrimaryKey(platform, live, desired, changes);
        diffNamedObjects(
                TableObjectType.UNIQUE_KEY, live.uniqueKeys(), desired.uniqueKeys(),
                UniqueKey::name, this::uniqueSignature, changes);
        diffNamedObjects(
                TableObjectType.CHECK_CONSTRAINT, live.checkConstraints(), desired.checkConstraints(),
                CheckConstraint::name, check -> checkSignature(platform, dialect, check), changes);
        diffNamedObjects(
                TableObjectType.INDEX, live.indexes(), desired.indexes(),
                Index::name, index -> indexSignature(dialect, index), changes);
        diffNamedObjects(
                TableObjectType.FOREIGN_KEY, live.foreignKeys(), desired.foreignKeys(),
                ForeignKey::name, foreignKey -> foreignKeySignature(desired, foreignKey), changes);
        return List.copyOf(changes);
    }

    private void diffPrimaryKey(
            DatabasePlatform platform, Table live, Table desired, List<TableObjectChange> changes) {
        PrimaryKey before = live.primaryKey().orElse(null);
        PrimaryKey after = desired.primaryKey().orElse(null);
        if (before == null && after == null) return;
        if (before == null) {
            changes.add(objectChange(TableObjectType.PRIMARY_KEY, TableObjectChangeKind.ADD,
                    after.name(), null, after, MigrationRisk.REVIEW,
                    "primary key is absent in live metadata and present in the desired document"));
            return;
        }
        if (after == null) {
            changes.add(objectChange(TableObjectType.PRIMARY_KEY, TableObjectChangeKind.DROP,
                    before.name(), before, null, MigrationRisk.DESTRUCTIVE,
                    "primary key is present in live metadata but absent from the desired document"));
            return;
        }
        boolean sameStructure = primarySignature(before).equals(primarySignature(after));
        boolean sameExplicitName = platform == DatabasePlatform.MYSQL
                || compatibleName(before.name(), after.name());
        if (!sameStructure || !sameExplicitName) {
            changes.add(objectChange(TableObjectType.PRIMARY_KEY, TableObjectChangeKind.REPLACE,
                    chooseName(after.name(), before.name()), before, after, MigrationRisk.DESTRUCTIVE,
                    "primary key definition changes; replacement is emitted as DROP then ADD"));
        }
    }

    private <T> void diffNamedObjects(
            TableObjectType objectType,
            List<T> live,
            List<T> desired,
            Function<T, Identifier> name,
            Function<T, String> signature,
            List<TableObjectChange> changes) {
        Set<Integer> matched = new LinkedHashSet<>();
        for (T after : desired) {
            Identifier afterName = name.apply(after);
            int match = findByName(live, matched, name, afterName);
            if (match < 0) match = findBySignature(live, matched, signature, signature.apply(after));
            if (match < 0) {
                changes.add(objectChange(objectType, TableObjectChangeKind.ADD, afterName,
                        null, after, addObjectRisk(objectType),
                        objectLabel(objectType) + " is absent in live metadata and present in the desired document"));
                continue;
            }
            matched.add(match);
            T before = live.get(match);
            boolean sameStructure = signature.apply(before).equals(signature.apply(after));
            boolean sameExplicitName = compatibleName(name.apply(before), afterName);
            if (!sameStructure || !sameExplicitName) {
                changes.add(objectChange(objectType, TableObjectChangeKind.REPLACE,
                        chooseName(afterName, name.apply(before)), before, after,
                        replaceObjectRisk(objectType),
                        objectLabel(objectType) + " definition changes; replacement is emitted as DROP then ADD"));
            }
        }
        for (int i = 0; i < live.size(); i++) {
            if (matched.contains(i)) continue;
            T before = live.get(i);
            changes.add(objectChange(objectType, TableObjectChangeKind.DROP, name.apply(before),
                    before, null, dropObjectRisk(objectType),
                    objectLabel(objectType) + " is present in live metadata but absent from the desired document"));
        }
    }

    private static <T> int findByName(
            List<T> values, Set<Integer> matched, Function<T, Identifier> name, Identifier wanted) {
        if (wanted == null) return -1;
        for (int i = 0; i < values.size(); i++) {
            if (matched.contains(i)) continue;
            Identifier candidate = name.apply(values.get(i));
            if (candidate != null && candidate.normalized().equals(wanted.normalized())) return i;
        }
        return -1;
    }

    private static <T> int findBySignature(
            List<T> values, Set<Integer> matched, Function<T, String> signature, String wanted) {
        for (int i = 0; i < values.size(); i++) {
            if (matched.contains(i)) continue;
            if (signature.apply(values.get(i)).equals(wanted)) return i;
        }
        return -1;
    }

    private static TableObjectChange objectChange(
            TableObjectType type, TableObjectChangeKind kind, Identifier name,
            Object before, Object after, MigrationRisk risk, String rationale) {
        return new TableObjectChange(type, kind, name, before, after, risk, rationale);
    }

    private static MigrationRisk addObjectRisk(TableObjectType type) {
        return type == TableObjectType.INDEX ? MigrationRisk.SAFE : MigrationRisk.REVIEW;
    }

    private static MigrationRisk dropObjectRisk(TableObjectType type) {
        return type == TableObjectType.INDEX ? MigrationRisk.REVIEW : MigrationRisk.DESTRUCTIVE;
    }

    private static MigrationRisk replaceObjectRisk(TableObjectType type) {
        return type == TableObjectType.INDEX ? MigrationRisk.REVIEW : MigrationRisk.DESTRUCTIVE;
    }

    private static String objectLabel(TableObjectType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static boolean compatibleName(Identifier before, Identifier after) {
        if (after == null) return true;
        return before != null && before.normalized().equals(after.normalized());
    }

    private static Identifier chooseName(Identifier preferred, Identifier fallback) {
        return preferred == null ? fallback : preferred;
    }

    private String primarySignature(PrimaryKey key) {
        return identifierList(key.columns()) + "|DEF=" + key.deferrable() + "|INIT=" + key.initiallyDeferred();
    }

    private String uniqueSignature(UniqueKey key) {
        return identifierList(key.columns()) + "|DEF=" + key.deferrable() + "|INIT=" + key.initiallyDeferred();
    }

    private String foreignKeySignature(Table owner, ForeignKey key) {
        String referencedSchema = key.referencedTable().schemaName()
                .map(Identifier::normalized)
                .orElseGet(() -> owner.qualifiedName().schemaName().map(Identifier::normalized).orElse(""));
        String referencedTable = (referencedSchema.isBlank() ? "" : referencedSchema + ".")
                + key.referencedTable().name().normalized();
        return identifierList(key.columns())
                + "->" + referencedTable
                + "(" + identifierList(key.referencedColumns()) + ")"
                + "|DEL=" + key.onDelete() + "|UPD=" + key.onUpdate()
                + "|DEF=" + key.deferrable() + "|INIT=" + key.initiallyDeferred()
                + "|PHYS=" + key.physicalReference();
    }

    private String checkSignature(DatabasePlatform platform, Dialect dialect, CheckConstraint check) {
        String expression;
        try {
            expression = dialect.expression(check.expression());
        } catch (UnsupportedOperationException unsupported) {
            expression = check.expression();
        }
        return normalizeCheckExpression(platform, expression);
    }

    static String normalizeCheckExpression(DatabasePlatform platform, String value) {
        if (value == null) return null;
        String normalized = value;
        if (platform == DatabasePlatform.MYSQL) {
            // MySQL information_schema CHECK_CLAUSE decorates identifiers with backticks and
            // ordinary UTF string literals with charset introducers such as _utf8mb4. Some
            // server/JDBC combinations also expose those literal delimiters as \' instead of '.
            // These are catalog-rendering details, not logical drift from the canonical CHECK.
            normalized = normalized.replaceAll("`([^`]+)`", "$1");
            normalized = normalizeMySqlCatalogEscapedLiterals(normalized);
            normalized = normalized.replaceAll("(?i)_(?:utf8mb4|utf8mb3)(?=')", "");
            return normalizeMySqlCheckFormatting(normalized);
        }
        if (platform == DatabasePlatform.POSTGRESQL) {
            // pg_get_constraintdef(..., true) lower-cases ordinary identifiers and can remove
            // redundant parentheses around individual boolean predicates. Preserve grouping
            // parentheses that contain AND/OR because those can change boolean precedence.
            return normalizePostgreSqlCheckFormatting(normalized);
        }
        if (platform == DatabasePlatform.SQLSERVER) {
            // SQL Server sys.check_constraints.definition commonly decorates ordinary identifiers
            // with brackets, wraps numeric scalar literals in parentheses, and removes whitespace
            // around comparison operators. Normalize only those catalog-rendering differences;
            // boolean grouping and string-literal contents remain significant.
            return normalizeSqlServerCheckFormatting(normalized);
        }
        return normalizeExpression(normalized);
    }

    /**
     * Reconstructs ordinary quoted literals when MySQL information_schema exposes
     * charset-prefixed CHECK literals with backslash-escaped quote delimiters, e.g.
     * {@code _utf8mb4\'A\'}. Only charset-prefixed catalog literals are rewritten.
     * Internal escaped apostrophes are converted to SQL-standard doubled apostrophes so
     * the later quote-aware formatter preserves their literal value.
     */
    static String normalizeMySqlCatalogEscapedLiterals(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean inCatalogLiteral = false;

        for (int i = 0; i < value.length();) {
            if (!inCatalogLiteral) {
                int introducerLength = mySqlCharsetIntroducerLength(value, i);
                int quoteSlash = i + introducerLength;
                if (introducerLength > 0
                        && quoteSlash + 1 < value.length()
                        && value.charAt(quoteSlash) == '\\'
                        && value.charAt(quoteSlash + 1) == '\'') {
                    out.append('\'');
                    i = quoteSlash + 2;
                    inCatalogLiteral = true;
                    continue;
                }
                out.append(value.charAt(i++));
                continue;
            }

            if (i + 1 < value.length() && value.charAt(i) == '\\' && value.charAt(i + 1) == '\'') {
                if (isMySqlCatalogLiteralTerminator(value, i + 2)) {
                    out.append('\'');
                    i += 2;
                    inCatalogLiteral = false;
                } else {
                    out.append("''");
                    i += 2;
                }
                continue;
            }

            out.append(value.charAt(i++));
        }
        return out.toString();
    }

    private static int mySqlCharsetIntroducerLength(String value, int offset) {
        if (regionMatchesIgnoreCase(value, offset, "_utf8mb4")) return 8;
        if (regionMatchesIgnoreCase(value, offset, "_utf8mb3")) return 8;
        return 0;
    }

    private static boolean regionMatchesIgnoreCase(String value, int offset, String token) {
        return offset >= 0
                && offset + token.length() <= value.length()
                && value.regionMatches(true, offset, token, 0, token.length());
    }

    private static boolean isMySqlCatalogLiteralTerminator(String value, int offset) {
        int i = offset;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        if (i >= value.length()) return true;

        char ch = value.charAt(i);
        if (ch == ',' || ch == ')' || ch == ';'
                || ch == '=' || ch == '<' || ch == '>' || ch == '!'
                || ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%') {
            return true;
        }

        int start = i;
        while (i < value.length() && (Character.isLetter(value.charAt(i)) || value.charAt(i) == '_')) i++;
        if (i == start) return false;
        String token = value.substring(start, i).toUpperCase(java.util.Locale.ROOT);
        return token.equals("AND") || token.equals("OR") || token.equals("IS") || token.equals("LIKE")
                || token.equals("REGEXP") || token.equals("RLIKE") || token.equals("BETWEEN")
                || token.equals("IN") || token.equals("NOT") || token.equals("COLLATE");
    }

    /**
     * Canonicalizes MySQL CHECK formatting without changing quoted literal contents.
     * information_schema may add/remove insignificant whitespace around commas and
     * parentheses compared with the authored document expression. A quote-aware scan
     * avoids accidentally changing values such as 'A, B'.
     */
    static String normalizeMySqlCheckFormatting(String value) {
        return normalizeCatalogCheckFormatting(value);
    }

    static String normalizePostgreSqlCheckFormatting(String value) {
        if (value == null) return null;
        String source = stripBalancedOuterParentheses(value.trim());
        source = stripPostgreSqlRedundantPredicateParentheses(source);
        return normalizeCatalogCheckFormatting(source);
    }

    static String normalizeSqlServerCheckFormatting(String value) {
        if (value == null) return null;
        String source = stripSqlServerSimpleIdentifierBrackets(value.trim());
        source = stripSqlServerNumericLiteralParentheses(source);
        source = stripBalancedOuterParentheses(source);
        // The predicate-parenthesis rule is dialect-neutral once SQL Server brackets are removed:
        // it removes only atomic boolean wrappers and preserves groups containing top-level AND/OR.
        source = stripPostgreSqlRedundantPredicateParentheses(source);
        return normalizeSqlServerOperatorSpacing(normalizeCatalogCheckFormatting(source));
    }

    /** Remove SQL Server bracket quoting only for ordinary identifier tokens such as [ID]. */
    static String stripSqlServerSimpleIdentifierBrackets(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean inString = false;
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (inString) {
                out.append(ch);
                if (ch == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    out.append(value.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (ch == '\'') inString = false;
                i++;
                continue;
            }
            if (ch == '\'') {
                inString = true;
                out.append(ch);
                i++;
                continue;
            }
            if (ch != '[') {
                out.append(ch);
                i++;
                continue;
            }

            int j = i + 1;
            StringBuilder inner = new StringBuilder();
            boolean closed = false;
            while (j < value.length()) {
                char current = value.charAt(j);
                if (current == ']') {
                    if (j + 1 < value.length() && value.charAt(j + 1) == ']') {
                        inner.append(']');
                        j += 2;
                        continue;
                    }
                    closed = true;
                    break;
                }
                inner.append(current);
                j++;
            }
            if (closed && isSimpleSqlServerIdentifier(inner.toString())) {
                out.append(inner);
                i = j + 1;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isSimpleSqlServerIdentifier(String value) {
        if (value == null || value.isEmpty()) return false;
        char first = value.charAt(0);
        if (!(Character.isLetter(first) || first == '_' || first == '@' || first == '#')) return false;
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '@' || ch == '#')) {
                return false;
            }
        }
        return true;
    }

    /** SQL Server catalog CHECK text often renders a scalar numeric literal as (0), (1), etc. */
    static String stripSqlServerNumericLiteralParentheses(String value) {
        if (value == null || value.isEmpty()) return value;
        String source = value;
        boolean changed;
        do {
            changed = false;
            StringBuilder out = new StringBuilder(source.length());
            boolean inString = false;
            for (int i = 0; i < source.length();) {
                char ch = source.charAt(i);
                if (inString) {
                    out.append(ch);
                    if (ch == '\'' && i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                        out.append(source.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ch == '\'') inString = false;
                    i++;
                    continue;
                }
                if (ch == '\'') {
                    inString = true;
                    out.append(ch);
                    i++;
                    continue;
                }
                if (ch == '(') {
                    int close = source.indexOf(')', i + 1);
                    if (close > i) {
                        String inner = source.substring(i + 1, close).trim();
                        if (isNumericLiteral(inner)) {
                            out.append(inner);
                            i = close + 1;
                            changed = true;
                            continue;
                        }
                    }
                }
                out.append(ch);
                i++;
            }
            source = out.toString();
        } while (changed);
        return source;
    }

    private static boolean isNumericLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        int i = 0;
        if (value.charAt(i) == '+' || value.charAt(i) == '-') i++;
        boolean digit = false;
        while (i < value.length() && Character.isDigit(value.charAt(i))) { digit = true; i++; }
        if (i < value.length() && value.charAt(i) == '.') {
            i++;
            while (i < value.length() && Character.isDigit(value.charAt(i))) { digit = true; i++; }
        }
        if (!digit) return false;
        if (i < value.length() && (value.charAt(i) == 'e' || value.charAt(i) == 'E')) {
            i++;
            if (i < value.length() && (value.charAt(i) == '+' || value.charAt(i) == '-')) i++;
            int exponentStart = i;
            while (i < value.length() && Character.isDigit(value.charAt(i))) i++;
            if (i == exponentStart) return false;
        }
        return i == value.length();
    }

    /** Canonicalize only whitespace adjacent to SQL operator characters, outside string literals. */
    static String normalizeSqlServerOperatorSpacing(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean inString = false;
        boolean inQuotedIdentifier = false;
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (inString) {
                out.append(ch);
                if (ch == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    out.append(value.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (ch == '\'') inString = false;
                i++;
                continue;
            }
            if (inQuotedIdentifier) {
                out.append(ch);
                if (ch == '"') inQuotedIdentifier = false;
                i++;
                continue;
            }
            if (ch == '\'') { inString = true; out.append(ch); i++; continue; }
            if (ch == '"') { inQuotedIdentifier = true; out.append(ch); i++; continue; }
            if (Character.isWhitespace(ch)) {
                int j = i;
                while (j < value.length() && Character.isWhitespace(value.charAt(j))) j++;
                char prev = out.length() == 0 ? '\0' : out.charAt(out.length() - 1);
                char next = j >= value.length() ? '\0' : value.charAt(j);
                if (!isSqlOperatorChar(prev) && !isSqlOperatorChar(next) && out.length() > 0 && j < value.length()) {
                    out.append(' ');
                }
                i = j;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static boolean isSqlOperatorChar(char ch) {
        return ch == '=' || ch == '<' || ch == '>' || ch == '!' || ch == '+' || ch == '-'
                || ch == '*' || ch == '/' || ch == '%';
    }

    /**
     * Removes only PostgreSQL catalog parentheses that wrap one atomic boolean predicate, e.g.
     * {@code (ID > 0) AND (PARENT_ID > 0)}. Parentheses containing a top-level AND/OR are kept
     * because removing them can change boolean precedence. Function calls, IN lists, arithmetic
     * groups, and other non-boolean-term parentheses are left untouched by context checks.
     */
    static String stripPostgreSqlRedundantPredicateParentheses(String value) {
        if (value == null || value.isBlank()) return value;
        String source = value;
        boolean changed;
        do {
            changed = false;
            int[] stack = new int[source.length()];
            int stackSize = 0;
            boolean inString = false;
            boolean inQuotedIdentifier = false;
            for (int i = 0; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (inString) {
                    if (ch == '\'' && i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                        i++;
                    } else if (ch == '\'') {
                        inString = false;
                    }
                    continue;
                }
                if (inQuotedIdentifier) {
                    if (ch == '"' && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        i++;
                    } else if (ch == '"') {
                        inQuotedIdentifier = false;
                    }
                    continue;
                }
                if (ch == '\'') {
                    inString = true;
                    continue;
                }
                if (ch == '"') {
                    inQuotedIdentifier = true;
                    continue;
                }
                if (ch == '(') {
                    stack[stackSize++] = i;
                    continue;
                }
                if (ch != ')' || stackSize == 0) continue;

                int open = stack[--stackSize];
                String inner = source.substring(open + 1, i).trim();
                if (inner.isEmpty() || containsTopLevelBooleanOperator(inner)) continue;
                if (!postgreSqlBooleanBoundaryBefore(source, open) || !postgreSqlBooleanBoundaryAfter(source, i)) {
                    continue;
                }
                source = source.substring(0, open) + inner + source.substring(i + 1);
                changed = true;
                break;
            }
        } while (changed);
        return source;
    }

    private static boolean postgreSqlBooleanBoundaryBefore(String value, int open) {
        int i = open - 1;
        while (i >= 0 && Character.isWhitespace(value.charAt(i))) i--;
        if (i < 0 || value.charAt(i) == '(') return true;
        int end = i + 1;
        while (i >= 0 && (Character.isLetter(value.charAt(i)) || value.charAt(i) == '_')) i--;
        if (end == i + 1) return false;
        String token = value.substring(i + 1, end).toUpperCase(Locale.ROOT);
        return token.equals("AND") || token.equals("OR");
    }

    private static boolean postgreSqlBooleanBoundaryAfter(String value, int close) {
        int i = close + 1;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        if (i >= value.length() || value.charAt(i) == ')') return true;
        int start = i;
        while (i < value.length() && (Character.isLetter(value.charAt(i)) || value.charAt(i) == '_')) i++;
        if (i == start) return false;
        String token = value.substring(start, i).toUpperCase(Locale.ROOT);
        return token.equals("AND") || token.equals("OR");
    }

    private static boolean containsTopLevelBooleanOperator(String value) {
        int depth = 0;
        boolean inString = false;
        boolean inQuotedIdentifier = false;
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (inString) {
                if (ch == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') i += 2;
                else {
                    if (ch == '\'') inString = false;
                    i++;
                }
                continue;
            }
            if (inQuotedIdentifier) {
                if (ch == '"' && i + 1 < value.length() && value.charAt(i + 1) == '"') i += 2;
                else {
                    if (ch == '"') inQuotedIdentifier = false;
                    i++;
                }
                continue;
            }
            if (ch == '\'') { inString = true; i++; continue; }
            if (ch == '"') { inQuotedIdentifier = true; i++; continue; }
            if (ch == '(') { depth++; i++; continue; }
            if (ch == ')') { depth--; i++; continue; }
            if (depth == 0 && (Character.isLetter(ch) || ch == '_')) {
                int start = i++;
                while (i < value.length() && (Character.isLetter(value.charAt(i)) || value.charAt(i) == '_')) i++;
                String token = value.substring(start, i).toUpperCase(Locale.ROOT);
                if (token.equals("AND") || token.equals("OR")) return true;
                continue;
            }
            i++;
        }
        return false;
    }

    /**
     * Quote-aware CHECK formatter shared by catalog-specific normalizers. SQL keywords and
     * unquoted identifiers are folded to upper case while string literals and quoted identifiers
     * retain their semantic case/content.
     */
    private static String normalizeCatalogCheckFormatting(String value) {
        if (value == null) return null;
        String source = stripBalancedOuterParentheses(value.trim());
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean inQuotedIdentifier = false;
        boolean pendingSpace = false;

        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString) {
                out.append(ch);
                if (ch == '\'') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                        out.append(source.charAt(++i));
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (inQuotedIdentifier) {
                out.append(ch);
                if (ch == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        out.append(source.charAt(++i));
                    } else {
                        inQuotedIdentifier = false;
                    }
                }
                continue;
            }

            if (ch == '\'') {
                appendPendingSpace(out, pendingSpace, ch);
                pendingSpace = false;
                out.append(ch);
                inString = true;
                continue;
            }
            if (ch == '"') {
                appendPendingSpace(out, pendingSpace, ch);
                pendingSpace = false;
                out.append(ch);
                inQuotedIdentifier = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (ch == '(' || ch == ')' || ch == ',') {
                trimTrailingSpace(out);
                out.append(ch);
                pendingSpace = false;
                continue;
            }

            appendPendingSpace(out, pendingSpace, ch);
            pendingSpace = false;
            out.append(Character.toUpperCase(ch));
        }
        return stripBalancedOuterParentheses(out.toString());
    }

    private static void appendPendingSpace(StringBuilder out, boolean pendingSpace, char next) {
        if (!pendingSpace || out.length() == 0) return;
        char previous = out.charAt(out.length() - 1);
        if (previous != '(' && previous != ',' && next != ')' && next != ',') out.append(' ');
    }

    private static void trimTrailingSpace(StringBuilder out) {
        while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
    }

    private static String stripBalancedOuterParentheses(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null) return null;
        boolean changed = true;
        while (changed && normalized.length() >= 2 && normalized.startsWith("(") && normalized.endsWith(")")) {
            String inner = normalized.substring(1, normalized.length() - 1).trim();
            if (balancedSql(inner)) normalized = inner;
            else changed = false;
        }
        return normalized;
    }

    private static boolean balancedSql(String value) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (inString) {
                if (ch == '\'') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '\'') i++;
                    else inString = false;
                }
                continue;
            }
            if (ch == '\'') {
                inString = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth < 0) return false;
            }
        }
        return depth == 0 && !inString;
    }

    private String indexSignature(Dialect dialect, Index index) {
        StringBuilder value = new StringBuilder(index.type().name()).append('|');
        for (IndexColumn column : index.columns()) {
            if (column.expressionBased()) {
                String expression;
                try {
                    expression = dialect.expression(column.expression());
                } catch (UnsupportedOperationException unsupported) {
                    expression = column.expression();
                }
                value.append("EXPR:").append(normalizeExpression(expression));
            } else {
                value.append("COL:").append(column.column().normalized());
            }
            value.append(':').append(column.direction()).append('|');
        }
        value.append("INCLUDE=").append(identifierList(index.includeColumns())).append('|');
        String predicate = index.predicate();
        if (predicate != null) {
            try {
                predicate = dialect.expression(predicate);
            } catch (UnsupportedOperationException ignored) {
                // raw canonical predicate remains valid evidence for comparison
            }
        }
        value.append("WHERE=").append(normalizeExpression(predicate));
        return value.toString();
    }

    private static String identifierList(List<Identifier> values) {
        return values.stream().map(Identifier::normalized).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static void requireSameTable(Table live, Table desired) {
        String liveName = live.qualifiedName().toString().toUpperCase(Locale.ROOT);
        String desiredName = desired.qualifiedName().toString().toUpperCase(Locale.ROOT);
        if (!liveName.equals(desiredName)) {
            throw new IllegalArgumentException(
                    "live and desired tables must have the same qualified name: "
                            + live.qualifiedName() + " vs " + desired.qualifiedName());
        }
    }

    private static Map<String, Column> byName(List<Column> columns) {
        Map<String, Column> values = new LinkedHashMap<>();
        for (Column column : columns) values.put(column.name().normalized(), column);
        return values;
    }

    private static Comparator<Column> columnOrder() {
        return Comparator.comparing(Column::ordinalPosition, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(column -> column.name().normalized());
    }

    private String liveType(DatabasePlatform platform, Dialect dialect, Table table, Column column) {
        if (platform == DatabasePlatform.MYSQL) {
            String nativeType = column.physicalOptions().get("MYSQL_NATIVE_COLUMN_TYPE");
            if (nativeType != null && !nativeType.isBlank()) return nativeType;
        }
        return dialect.sqlType(table, column);
    }

    private String desiredType(Dialect dialect, Table table, Column column) {
        return dialect.sqlType(table, column);
    }

    private static MigrationRisk addRisk(Column column) {
        if (column.identity() || column.generated()) return MigrationRisk.REVIEW;
        if (!column.nullable() && !column.defaultValue().isPresent()) return MigrationRisk.REVIEW;
        if (!column.nullable()) return MigrationRisk.REVIEW;
        return MigrationRisk.SAFE;
    }

    private static String addRationale(Column column) {
        if (column.identity()) return "new identity column requires DBMS-specific operational review";
        if (column.generated()) return "new generated column requires expression review";
        if (!column.nullable() && !column.defaultValue().isPresent()) {
            return "new NOT NULL column has no default; existing rows may prevent migration";
        }
        if (!column.nullable()) return "new NOT NULL column has a default; review rewrite/locking impact";
        return "new nullable column is additive";
    }

    private static MigrationRisk typeRisk(String liveType, String desiredType) {
        ParsedType live = ParsedType.parse(liveType);
        ParsedType desired = ParsedType.parse(desiredType);
        if (live.sameBase(desired)) {
            if (live.firstArgument != null && desired.firstArgument != null) {
                if (isCharacterFamily(live.base)) {
                    if (desired.firstArgument < live.firstArgument) return MigrationRisk.DESTRUCTIVE;
                    if (desired.firstArgument > live.firstArgument) return MigrationRisk.SAFE;
                }
                if (isExactNumericFamily(live.base)) {
                    int liveScale = live.secondArgument == null ? 0 : live.secondArgument;
                    int desiredScale = desired.secondArgument == null ? 0 : desired.secondArgument;
                    if (desired.firstArgument < live.firstArgument || desiredScale < liveScale) {
                        return MigrationRisk.DESTRUCTIVE;
                    }
                    if (desiredScale == liveScale && desired.firstArgument > live.firstArgument) {
                        return MigrationRisk.SAFE;
                    }
                    return MigrationRisk.REVIEW;
                }
            }
        }
        return MigrationRisk.REVIEW;
    }

    private static boolean isCharacterFamily(String base) {
        return base.contains("CHAR") || base.contains("VARCHAR") || base.contains("GRAPHIC");
    }

    private static boolean isExactNumericFamily(String base) {
        return base.equals("NUMBER") || base.equals("NUMERIC") || base.equals("DECIMAL") || base.equals("DEC");
    }

    private static boolean sameDefault(DatabasePlatform platform, Dialect dialect, Column live, Column desired) {
        String left = live.defaultValue().isPresent()
                ? normalizeDefault(platform, live.defaultValue().expression()) : null;
        String right = effectiveDesiredDefault(platform, dialect, desired);
        return Objects.equals(left, right);
    }

    private static String effectiveDesiredDefault(DatabasePlatform platform, Dialect dialect, Column desired) {
        if (!desired.defaultValue().isPresent()) return null;
        if (platform == DatabasePlatform.MYSQL && desired.identity()) return null;

        String source = desired.defaultValue().expression();
        try {
            return normalizeDefault(platform, dialect.expression(source));
        } catch (UnsupportedOperationException unsupported) {
            return normalizeDefault(platform, source);
        }
    }

    private static String normalizeDefault(DatabasePlatform platform, String value) {
        String normalized = normalizeExpression(value);
        if (normalized == null) return null;
        if (platform == DatabasePlatform.POSTGRESQL) {
            Matcher literalCast = Pattern.compile(
                    "^('(?:[^']|'')*'|[-+]?\\d+(?:\\.\\d+)?)::[A-Z0-9_ ]+(?:\\(\\d+(?:,\\d+)?\\))?$")
                    .matcher(normalized);
            if (literalCast.matches()) return literalCast.group(1);
        }
        return normalized;
    }

    private static String defaultLabel(Column column) {
        return column.defaultValue().isPresent() ? column.defaultValue().expression() : "<none>";
    }

    static String normalizeExpression(String value) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        boolean changed = true;
        while (changed && normalized.length() >= 2 && normalized.startsWith("(") && normalized.endsWith(")")) {
            String inner = normalized.substring(1, normalized.length() - 1).trim();
            if (balanced(inner)) normalized = inner;
            else changed = false;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static boolean balanced(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth < 0) return false;
            }
        }
        return depth == 0;
    }

    static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s+", " ");
    }

    private record ParsedType(String base, Integer firstArgument, Integer secondArgument) {
        static ParsedType parse(String value) {
            String normalized = normalizeType(value);
            Matcher matcher = TYPE_ARGUMENTS.matcher(normalized);
            if (!matcher.matches()) return new ParsedType(normalized, null, null);
            String base = matcher.group(1).trim();
            Integer first = Integer.valueOf(matcher.group(2));
            Integer second = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
            return new ParsedType(base, first, second);
        }

        boolean sameBase(ParsedType other) {
            return base.equals(other.base);
        }
    }
}
