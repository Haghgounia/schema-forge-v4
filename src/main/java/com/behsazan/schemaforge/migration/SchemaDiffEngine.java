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
                CheckConstraint::name, check -> checkSignature(dialect, check), changes);
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

    private String checkSignature(Dialect dialect, CheckConstraint check) {
        try {
            return normalizeExpression(dialect.expression(check.expression()));
        } catch (UnsupportedOperationException unsupported) {
            return normalizeExpression(check.expression());
        }
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
