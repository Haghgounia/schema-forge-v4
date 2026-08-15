package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Loads unique canonical tables from one snapshot file or a directory of canonical snapshot files.
 *
 * <p>This is a production loader, so historical version selection is intentionally forbidden.
 * Every qualified table may occur only once in the input. Historical regression corpora must be
 * reduced to an explicit one-version-per-table input before this loader is used.</p>
 */
public final class CanonicalJsonDiagramInputLoader {
    private final CanonicalSnapshotJsonStore store;
    private final CanonicalSnapshotMapper mapper;

    public CanonicalJsonDiagramInputLoader() {
        this(new CanonicalSnapshotJsonStore(), new CanonicalSnapshotMapper());
    }

    CanonicalJsonDiagramInputLoader(CanonicalSnapshotJsonStore store, CanonicalSnapshotMapper mapper) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** Loads all unique tables from the supplied canonical snapshot file or directory. */
    public List<Table> loadTables(Path input) throws IOException {
        Objects.requireNonNull(input, "input must not be null");
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IllegalArgumentException("Canonical diagram input does not exist: " + normalized);
        }

        List<Path> snapshots = snapshotFiles(normalized);
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException(
                    "No canonical *.schema.json snapshots found under: " + normalized);
        }

        Map<String, LoadedTable> unique = new LinkedHashMap<>();
        for (Path snapshotPath : snapshots) {
            CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
            DatabaseSchema schema = mapper.toDomain(snapshot);
            for (Table table : schema.tables()) {
                String key = table.qualifiedName().toString().toUpperCase(Locale.ROOT);
                LoadedTable previous = unique.putIfAbsent(key, new LoadedTable(table, snapshotPath));
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "INPUT_DUPLICATE_TABLE: " + table.qualifiedName()
                                    + " appears in both " + previous.source()
                                    + " and " + snapshotPath
                                    + ". Production diagram input requires exactly one definition per qualified table.");
                }
            }
        }

        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Canonical diagram input contains no tables: " + normalized);
        }

        return unique.values().stream()
                .map(LoadedTable::table)
                .sorted(Comparator.comparing(table -> table.qualifiedName().toString().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private List<Path> snapshotFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) {
            if (!isSnapshot(input)) {
                throw new IllegalArgumentException(
                        "Canonical diagram input file must end with .schema.json: " + input);
            }
            return List.of(input);
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Canonical diagram input is not a file or directory: " + input);
        }
        List<Path> result = new ArrayList<>();
        try (var paths = Files.walk(input)) {
            paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonDiagramInputLoader::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(input.relativize(path))))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static boolean isSnapshot(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record LoadedTable(Table table, Path source) {
    }
}
