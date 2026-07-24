package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Canonical phase-1 schema model extracted from a Word specification. */
public final class DatabaseSchema {
    private final Identifier name;
    private final Description description;
    private final List<Table> tables;
    private final List<Sequence> sequences;
    private final Map<String, String> metadata;

    private DatabaseSchema(Builder builder) {
        this.name = builder.name;
        this.description = builder.description == null ? Description.empty() : builder.description;
        this.tables = List.copyOf(builder.tables);
        this.sequences = List.copyOf(builder.sequences);
        this.metadata = Map.copyOf(builder.metadata);
        ensureUnique(tables.stream().map(Table::qualifiedName).toList(), "table");
        ensureUnique(sequences.stream().map(Sequence::qualifiedName).toList(), "sequence");
    }

    private static void ensureUnique(List<QualifiedName> names, String type) {
        Set<String> seen = new HashSet<>();
        for (QualifiedName name : names) {
            if (!seen.add(name.toString().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate " + type + ": " + name);
            }
        }
    }

    public static Builder builder(String name) { return new Builder(Identifier.of(name)); }
    public Identifier name() { return name; }
    public Description description() { return description; }
    public List<Table> tables() { return tables; }
    public List<Sequence> sequences() { return sequences; }
    public Map<String, String> metadata() { return metadata; }

    public Optional<Table> findTable(String tableName) {
        String normalized = Identifier.of(tableName).normalized();
        return tables.stream()
                .filter(table -> table.qualifiedName().name().normalized().equals(normalized))
                .findFirst();
    }

    public static final class Builder {
        private final Identifier name;
        private Description description;
        private final List<Table> tables = new ArrayList<>();
        private final List<Sequence> sequences = new ArrayList<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(Identifier name) { this.name = name; }
        public Builder description(String value) { this.description = new Description(value); return this; }
        public Builder addTable(Table value) { tables.add(Objects.requireNonNull(value)); return this; }
        public Builder addSequence(Sequence value) { sequences.add(Objects.requireNonNull(value)); return this; }
        public Builder metadata(String key, String value) {
            metadata.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
            return this;
        }
        public DatabaseSchema build() { return new DatabaseSchema(this); }
    }
}
