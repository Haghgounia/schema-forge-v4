package com.behsazan.schemaforge.diagram;

import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable options for database-independent diagram export.
 *
 * <p>The options deliberately reference the canonical model rather than any SQL dialect.</p>
 */
public final class DiagramExportOptions {
    private final DiagramType type;
    private final DiagramScope scope;
    private final Identifier schema;
    private final QualifiedName rootTable;
    private final Set<QualifiedName> selectedTables;
    private final int dependencyDepth;
    private final boolean includeColumns;
    private final boolean includeDataTypes;
    private final boolean includePrimaryKeys;
    private final boolean includeForeignKeys;
    private final boolean includeLogicalForeignKeys;

    private DiagramExportOptions(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "diagram type must not be null");
        this.scope = Objects.requireNonNull(builder.scope, "diagram scope must not be null");
        this.schema = builder.schema;
        this.rootTable = builder.rootTable;
        this.selectedTables = Set.copyOf(builder.selectedTables);
        this.dependencyDepth = builder.dependencyDepth;
        this.includeColumns = builder.includeColumns;
        this.includeDataTypes = builder.includeDataTypes;
        this.includePrimaryKeys = builder.includePrimaryKeys;
        this.includeForeignKeys = builder.includeForeignKeys;
        this.includeLogicalForeignKeys = builder.includeLogicalForeignKeys;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DiagramExportOptions erAll() {
        return builder().type(DiagramType.ER).scope(DiagramScope.ALL).build();
    }

    private void validate() {
        if (dependencyDepth < 0) {
            throw new IllegalArgumentException("dependencyDepth must not be negative");
        }
        switch (scope) {
            case SCHEMA -> Objects.requireNonNull(schema, "schema is required for SCHEMA scope");
            case TABLE, TABLE_WITH_DEPENDENCIES ->
                    Objects.requireNonNull(rootTable, "rootTable is required for " + scope + " scope");
            case SELECTED_TABLES -> {
                if (selectedTables.isEmpty()) {
                    throw new IllegalArgumentException("selectedTables must not be empty for SELECTED_TABLES scope");
                }
            }
            case ALL -> {
                // no additional selector is required
            }
        }
    }

    public DiagramType type() { return type; }
    public DiagramScope scope() { return scope; }
    public Identifier schema() { return schema; }
    public QualifiedName rootTable() { return rootTable; }
    public Set<QualifiedName> selectedTables() { return selectedTables; }
    public int dependencyDepth() { return dependencyDepth; }
    public boolean includeColumns() { return includeColumns; }
    public boolean includeDataTypes() { return includeDataTypes; }
    public boolean includePrimaryKeys() { return includePrimaryKeys; }
    public boolean includeForeignKeys() { return includeForeignKeys; }
    public boolean includeLogicalForeignKeys() { return includeLogicalForeignKeys; }

    public static final class Builder {
        private DiagramType type = DiagramType.ER;
        private DiagramScope scope = DiagramScope.ALL;
        private Identifier schema;
        private QualifiedName rootTable;
        private final Set<QualifiedName> selectedTables = new LinkedHashSet<>();
        private int dependencyDepth = 1;
        private boolean includeColumns = true;
        private boolean includeDataTypes = true;
        private boolean includePrimaryKeys = true;
        private boolean includeForeignKeys = true;
        private boolean includeLogicalForeignKeys;

        private Builder() { }

        public Builder type(DiagramType value) {
            this.type = Objects.requireNonNull(value);
            return this;
        }

        public Builder scope(DiagramScope value) {
            this.scope = Objects.requireNonNull(value);
            return this;
        }

        public Builder schema(String value) {
            this.schema = value == null || value.isBlank() ? null : Identifier.of(value);
            return this;
        }

        public Builder rootTable(QualifiedName value) {
            this.rootTable = value;
            return this;
        }

        public Builder rootTable(String schema, String table) {
            this.rootTable = QualifiedName.of(schema, table);
            return this;
        }

        public Builder selectedTable(QualifiedName value) {
            this.selectedTables.add(Objects.requireNonNull(value));
            return this;
        }

        public Builder selectedTables(Collection<QualifiedName> values) {
            Objects.requireNonNull(values).forEach(this::selectedTable);
            return this;
        }

        public Builder dependencyDepth(int value) {
            this.dependencyDepth = value;
            return this;
        }

        public Builder includeColumns(boolean value) {
            this.includeColumns = value;
            return this;
        }

        public Builder includeDataTypes(boolean value) {
            this.includeDataTypes = value;
            return this;
        }

        public Builder includePrimaryKeys(boolean value) {
            this.includePrimaryKeys = value;
            return this;
        }

        public Builder includeForeignKeys(boolean value) {
            this.includeForeignKeys = value;
            return this;
        }

        public Builder includeLogicalForeignKeys(boolean value) {
            this.includeLogicalForeignKeys = value;
            return this;
        }

        public DiagramExportOptions build() {
            return new DiagramExportOptions(this);
        }
    }
}
