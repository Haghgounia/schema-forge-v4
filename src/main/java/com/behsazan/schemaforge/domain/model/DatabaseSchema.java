package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DatabaseSchema {
    private final Identifier name;
    private final Description description;
    private final List<Table> tables;
    private final List<Sequence> sequences;
    private final List<View> views;
    private final List<Synonym> synonyms;
    private final List<Trigger> triggers;
    private final List<Routine> routines;
    private final List<Grant> grants;
    private final Map<String,String> metadata;

    private DatabaseSchema(Builder b) {
        name = b.name; description = b.description == null ? Description.empty() : b.description;
        tables = List.copyOf(b.tables); sequences = List.copyOf(b.sequences); views = List.copyOf(b.views);
        synonyms = List.copyOf(b.synonyms); triggers = List.copyOf(b.triggers); routines = List.copyOf(b.routines); grants = List.copyOf(b.grants); metadata = Map.copyOf(b.metadata);
        ensureUnique(tables.stream().map(Table::qualifiedName).toList(), "table");
        ensureUnique(sequences.stream().map(Sequence::qualifiedName).toList(), "sequence");
        ensureUnique(views.stream().map(View::qualifiedName).toList(), "view");
    }
    private static void ensureUnique(List<QualifiedName> names, String type) {
        Set<String> seen = new HashSet<>(); for (QualifiedName n : names) if (!seen.add(n.toString().toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate " + type + ": " + n);
    }
    public static Builder builder(String name) { return new Builder(Identifier.of(name)); }
    public Identifier name() { return name; } public Description description() { return description; }
    public List<Table> tables() { return tables; } public List<Sequence> sequences() { return sequences; }
    public List<View> views() { return views; } public List<Synonym> synonyms() { return synonyms; }
    public List<Trigger> triggers() { return triggers; } public List<Routine> routines() { return routines; }
    public List<Grant> grants() { return grants; }
    public Map<String,String> metadata() { return metadata; }
    public Optional<Table> findTable(String tableName) { String n = Identifier.of(tableName).normalized(); return tables.stream().filter(t -> t.qualifiedName().name().normalized().equals(n)).findFirst(); }

    public static final class Builder {
        private final Identifier name; private Description description;
        private final List<Table> tables = new ArrayList<>(); private final List<Sequence> sequences = new ArrayList<>();
        private final List<View> views = new ArrayList<>(); private final List<Synonym> synonyms = new ArrayList<>();
        private final List<Trigger> triggers = new ArrayList<>(); private final List<Routine> routines = new ArrayList<>();
        private final List<Grant> grants = new ArrayList<>();
        private final Map<String,String> metadata = new LinkedHashMap<>();
        private Builder(Identifier name) { this.name = name; }
        public Builder description(String value) { description = new Description(value); return this; }
        public Builder addTable(Table value) { tables.add(Objects.requireNonNull(value)); return this; }
        public Builder addSequence(Sequence value) { sequences.add(Objects.requireNonNull(value)); return this; }
        public Builder addView(View value) { views.add(Objects.requireNonNull(value)); return this; }
        public Builder addSynonym(Synonym value) { synonyms.add(Objects.requireNonNull(value)); return this; }
        public Builder addTrigger(Trigger value) { triggers.add(Objects.requireNonNull(value)); return this; }
        public Builder addRoutine(Routine value) { routines.add(Objects.requireNonNull(value)); return this; }
        public Builder addGrant(Grant value) { grants.add(Objects.requireNonNull(value)); return this; }
        public Builder metadata(String key, String value) { metadata.put(Objects.requireNonNull(key), Objects.requireNonNull(value)); return this; }
        public DatabaseSchema build() { return new DatabaseSchema(this); }
    }
}
