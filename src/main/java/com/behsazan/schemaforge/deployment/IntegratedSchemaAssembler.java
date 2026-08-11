package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Merges canonical inputs into one deployable schema without guessing between historical versions.
 *
 * <p>Integrated deployment has a strict input contract: each qualified table and sequence may be
 * defined only once. Historical regression corpora may intentionally contain several versions of
 * the same table, but such corpora are not valid integrated-deployment input.</p>
 */
public final class IntegratedSchemaAssembler {

    /** Merges canonical schemas and fails immediately when a qualified object is defined twice. */
    public DatabaseSchema assemble(String name, List<DatabaseSchema> inputs) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");

        Map<String, Table> tables = new LinkedHashMap<>();
        Map<String, Sequence> sequences = new LinkedHashMap<>();
        for (DatabaseSchema input : inputs) {
            Objects.requireNonNull(input, "input schema must not be null");
            for (Table table : input.tables()) {
                String key = key(table.qualifiedName());
                Table previous = tables.putIfAbsent(key, table);
                if (previous != null) {
                    throw new IllegalArgumentException("INPUT_DUPLICATE_TABLE: " + table.qualifiedName());
                }
            }
            for (Sequence sequence : input.sequences()) {
                String key = key(sequence.qualifiedName());
                Sequence previous = sequences.putIfAbsent(key, sequence);
                if (previous != null) {
                    throw new IllegalArgumentException("INPUT_DUPLICATE_SEQUENCE: " + sequence.qualifiedName());
                }
            }
        }

        DatabaseSchema.Builder builder = DatabaseSchema.builder(name);
        tables.values().forEach(builder::addTable);
        sequences.values().forEach(builder::addSequence);
        return builder.build();
    }

    private static String key(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }
}
