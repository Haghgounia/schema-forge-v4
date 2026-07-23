package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Table implements SchemaObject {
    private final QualifiedName qualifiedName;
    private final Description description;
    private final List<Column> columns;
    private final PrimaryKey primaryKey;
    private final List<ForeignKey> foreignKeys;
    private final List<UniqueKey> uniqueKeys;
    private final List<CheckConstraint> checkConstraints;
    private final List<Index> indexes;
    private final Map<String, String> physicalOptions;

    private Table(Builder b) {
        this.qualifiedName = Objects.requireNonNull(b.qualifiedName);
        this.description = b.description == null ? Description.empty() : b.description;
        this.columns = List.copyOf(b.columns);
        this.primaryKey = b.primaryKey;
        this.foreignKeys = List.copyOf(b.foreignKeys);
        this.uniqueKeys = List.copyOf(b.uniqueKeys);
        this.checkConstraints = List.copyOf(b.checkConstraints);
        this.indexes = List.copyOf(b.indexes);
        this.physicalOptions = Map.copyOf(b.physicalOptions);
        validate();
    }

    private void validate() {
        if (columns.isEmpty()) throw new IllegalArgumentException("table must contain at least one column");
        Map<String, Column> byName = columns.stream().collect(Collectors.toMap(c -> c.name().normalized(), Function.identity(), (a,b) -> { throw new IllegalArgumentException("duplicate column: " + a.name()); }));
        if (primaryKey != null) requireColumns(primaryKey.columns(), byName, "primary key");
        foreignKeys.forEach(fk -> requireColumns(fk.columns(), byName, "foreign key"));
        uniqueKeys.forEach(uk -> requireColumns(uk.columns(), byName, "unique key"));
        indexes.forEach(idx -> requireColumns(idx.columns().stream().map(IndexColumn::column).toList(), byName, "index"));
    }
    private static void requireColumns(List<Identifier> names, Map<String, Column> columns, String owner) {
        names.forEach(n -> { if (!columns.containsKey(n.normalized())) throw new IllegalArgumentException(owner + " references missing column: " + n); });
    }

    public static Builder builder(String schema, String name) { return new Builder(QualifiedName.of(schema, name)); }
    @Override public QualifiedName qualifiedName() { return qualifiedName; }
    @Override public ObjectType objectType() { return ObjectType.TABLE; }
    @Override public Description description() { return description; }
    public List<Column> columns() { return columns; }
    public Optional<PrimaryKey> primaryKey() { return Optional.ofNullable(primaryKey); }
    public List<ForeignKey> foreignKeys() { return foreignKeys; }
    public List<UniqueKey> uniqueKeys() { return uniqueKeys; }
    public List<CheckConstraint> checkConstraints() { return checkConstraints; }
    public List<Index> indexes() { return indexes; }
    public Map<String,String> physicalOptions() { return physicalOptions; }
    public Optional<Column> findColumn(String name) { String n = Identifier.of(name).normalized(); return columns.stream().filter(c -> c.name().normalized().equals(n)).findFirst(); }

    public static final class Builder {
        private final QualifiedName qualifiedName;
        private Description description;
        private final List<Column> columns = new ArrayList<>();
        private PrimaryKey primaryKey;
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final List<UniqueKey> uniqueKeys = new ArrayList<>();
        private final List<CheckConstraint> checkConstraints = new ArrayList<>();
        private final List<Index> indexes = new ArrayList<>();
        private final Map<String,String> physicalOptions = new LinkedHashMap<>();
        private Builder(QualifiedName qualifiedName) { this.qualifiedName = qualifiedName; }
        public Builder description(String value) { this.description = new Description(value); return this; }
        public Builder addColumn(Column value) { columns.add(Objects.requireNonNull(value)); return this; }
        public Builder primaryKey(PrimaryKey value) { this.primaryKey = value; return this; }
        public Builder addForeignKey(ForeignKey value) { foreignKeys.add(Objects.requireNonNull(value)); return this; }
        public Builder addUniqueKey(UniqueKey value) { uniqueKeys.add(Objects.requireNonNull(value)); return this; }
        public Builder addCheck(CheckConstraint value) { checkConstraints.add(Objects.requireNonNull(value)); return this; }
        public Builder addIndex(Index value) { indexes.add(Objects.requireNonNull(value)); return this; }
        public Builder physicalOption(String key, String value) { physicalOptions.put(Objects.requireNonNull(key), Objects.requireNonNull(value)); return this; }
        public Table build() { return new Table(this); }
    }
}
