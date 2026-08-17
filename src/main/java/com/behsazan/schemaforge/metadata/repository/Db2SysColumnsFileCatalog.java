package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only offline catalog backed by a CSV/TSV/TXT export of SYSIBM.SYSCOLUMNS.
 *
 * <p>The catalog is intentionally strict: lookups are exact on creator, table and column
 * (case-insensitive), and conflicting duplicate rows are treated as ambiguous rather than
 * guessed. The file is loaded once because targeted recovery may parse hundreds of Word
 * documents in one run.</p>
 */
public final class Db2SysColumnsFileCatalog {
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "SMALLINT", "INTEGER", "BIGINT", "TIME", "DATE", "ROWID", "XML");
    private static final Set<String> LENGTH_TYPES = Set.of(
            "CHAR", "VARCHAR", "GRAPHIC", "VARG", "BINARY", "VARBIN");
    private static final Set<String> LONG_LENGTH_TYPES = Set.of(
            "LONGVAR", "LONGVARG", "BLOB", "CLOB", "DBCLOB");
    private static final Set<String> NUMERIC_TYPES = Set.of("DECIMAL", "NUMERIC");

    private final Map<Key, DataType> types;
    private final String sourceFileName;
    private final Set<Key> ambiguous;
    private final Set<Key> sourceColumns;
    private final Set<TableKey> sourceTables;
    private final Set<Key> incomplete;
    private final int sourceRows;

    public Db2SysColumnsFileCatalog(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Db2 SYSCOLUMNS metadata file does not exist: " + normalized);
        }
        LoadResult loaded = load(normalized);
        this.sourceFileName = normalized.getFileName().toString();
        this.types = Map.copyOf(loaded.types());
        this.ambiguous = Set.copyOf(loaded.ambiguous());
        this.sourceColumns = Set.copyOf(loaded.sourceColumns());
        this.sourceTables = Set.copyOf(loaded.sourceTables());
        this.incomplete = Set.copyOf(loaded.incomplete());
        this.sourceRows = loaded.sourceRows();
        if (types.isEmpty() && ambiguous.isEmpty()) {
            throw new IllegalArgumentException("No usable SYSIBM.SYSCOLUMNS rows were found in " + normalized);
        }
    }

    public Optional<DataType> findType(String schemaName, String tableName, String columnName) {
        Key key = Key.of(schemaName, tableName, columnName);
        if (key == null || ambiguous.contains(key)) return Optional.empty();
        return Optional.ofNullable(types.get(key));
    }

    /**
     * Explains why an exact schema/table/column lookup can or cannot be used for recovery.
     * This method never performs fuzzy matching.
     */
    public LookupStatus lookupStatus(String schemaName, String tableName, String columnName) {
        Key key = Key.of(schemaName, tableName, columnName);
        if (key == null) return LookupStatus.INVALID_KEY;
        if (ambiguous.contains(key)) return LookupStatus.AMBIGUOUS;
        if (types.containsKey(key)) return LookupStatus.USABLE;
        if (incomplete.contains(key) || sourceColumns.contains(key)) return LookupStatus.INCOMPLETE;
        TableKey table = TableKey.of(schemaName, tableName);
        if (table == null) return LookupStatus.INVALID_KEY;
        return sourceTables.contains(table) ? LookupStatus.COLUMN_NOT_FOUND : LookupStatus.TABLE_NOT_FOUND;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public int sourceRows() {
        return sourceRows;
    }

    public int usableColumns() {
        return types.size();
    }

    public int ambiguousColumns() {
        return ambiguous.size();
    }

    private static LoadResult load(Path file) {
        Map<Key, DataType> result = new LinkedHashMap<>();
        Set<Key> ambiguous = new HashSet<>();
        Set<Key> sourceColumns = new HashSet<>();
        Set<TableKey> sourceTables = new HashSet<>();
        Set<Key> incomplete = new HashSet<>();
        int rows = 0;
        try {
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory() || !isTextEntry(entry.getName())) continue;
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        zip.transferTo(buffer);
                        rows += loadText(buffer.toString(StandardCharsets.UTF_8), result, ambiguous,
                                sourceColumns, sourceTables, incomplete);
                    }
                }
            } else {
                rows = loadText(Files.readString(file, StandardCharsets.UTF_8), result, ambiguous,
                        sourceColumns, sourceTables, incomplete);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Db2 SYSCOLUMNS metadata file: " + file, exception);
        }
        ambiguous.forEach(result::remove);
        return new LoadResult(result, ambiguous, sourceColumns, sourceTables, incomplete, rows);
    }

    private static boolean isTextEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt");
    }

    private static int loadText(String text, Map<Key, DataType> result, Set<Key> ambiguous,
                                Set<Key> sourceColumns, Set<TableKey> sourceTables, Set<Key> incomplete) {
        if (text == null || text.isBlank()) return 0;
        text = stripBom(text);
        char delimiter = detectDelimiter(text);
        List<List<String>> records = parseDelimited(text, delimiter);
        if (records.isEmpty()) return 0;

        Header header = Header.from(records.getFirst());
        if (!header.usable()) return 0;
        int acceptedRows = 0;
        for (int index = 1; index < records.size(); index++) {
            List<String> row = records.get(index);
            if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
            acceptedRows++;
            Key key = Key.of(
                    value(row, header.schema()),
                    value(row, header.table()),
                    value(row, header.column()));
            if (key == null) continue;
            sourceColumns.add(key);
            sourceTables.add(new TableKey(key.schema(), key.table()));
            String rawType = normalizeType(value(row, header.type()));
            Integer length = integer(value(row, header.length()));
            Integer length2 = integer(value(row, header.length2()));
            Integer scale = integer(value(row, header.scale()));
            String typeName = trimToNull(value(row, header.typeName()));
            if (!sufficientEvidence(rawType, length, length2, scale, header.scale() >= 0, typeName)) {
                incomplete.add(key);
                continue;
            }
            DataType type = Db2ZosCatalogTypeMapper.mapDataType(
                    rawType, length, length2, scale, typeName);
            DataType previous = result.putIfAbsent(key, type);
            if (previous != null && !previous.equals(type)) {
                ambiguous.add(key);
            }
        }
        return acceptedRows;
    }

    private static boolean sufficientEvidence(String type, Integer length, Integer length2,
                                              Integer scale, boolean scaleColumnPresent,
                                              String typeName) {
        if (type == null) return false;
        if (SIMPLE_TYPES.contains(type)) return true;
        if (LENGTH_TYPES.contains(type)) return positive(length);
        if (LONG_LENGTH_TYPES.contains(type)) return positive(length2) || positive(length);
        if (NUMERIC_TYPES.contains(type)) return positive(length) && scaleColumnPresent && scale != null && scale >= 0;
        if (type.equals("FLOAT") || type.equals("DECFLOAT")) return positive(length);
        if (type.equals("TIMESTMP") || type.equals("TIMESTZ")) return true;
        if (type.equals("DISTINCT")) return typeName != null;
        return false;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static String normalizeType(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        char best = ',';
        int bestCount = -1;
        for (char candidate : new char[]{',', '\t', ';', '|'}) {
            int count = countOutsideQuotes(first, candidate);
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String value, char delimiter) {
        boolean quoted = false;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && ch == delimiter) {
                count++;
            }
        }
        return count;
    }

    private static List<List<String>> parseDelimited(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && ch == delimiter) {
                row.add(cell.toString().trim());
                cell.setLength(0);
                continue;
            }
            if (!quoted && (ch == '\n' || ch == '\r')) {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
                continue;
            }
            cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static String value(List<String> row, int index) {
        return index < 0 || index >= row.size() ? null : row.get(index);
    }

    private static Integer integer(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Key(String schema, String table, String column) {
        static Key of(String schema, String table, String column) {
            String s = normalize(schema);
            String t = normalize(table);
            String c = normalize(column);
            return s == null || t == null || c == null ? null : new Key(s, t, c);
        }

        private static String normalize(String value) {
            String trimmed = trimToNull(value);
            return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
        }
    }

    public enum LookupStatus {
        USABLE,
        AMBIGUOUS,
        INCOMPLETE,
        COLUMN_NOT_FOUND,
        TABLE_NOT_FOUND,
        INVALID_KEY
    }

    private record TableKey(String schema, String table) {
        static TableKey of(String schema, String table) {
            String s = Key.normalize(schema);
            String t = Key.normalize(table);
            return s == null || t == null ? null : new TableKey(s, t);
        }
    }

    private record LoadResult(Map<Key, DataType> types, Set<Key> ambiguous,
                              Set<Key> sourceColumns, Set<TableKey> sourceTables,
                              Set<Key> incomplete, int sourceRows) { }

    private record Header(int schema, int table, int column, int type, int length,
                          int length2, int scale, int typeName) {
        static Header from(List<String> values) {
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < values.size(); i++) {
                indexes.put(normalizeHeader(values.get(i)), i);
            }
            return new Header(
                    first(indexes, "TBCREATOR", "TABLE_SCHEMA", "CREATOR"),
                    first(indexes, "TBNAME", "TABLE_NAME"),
                    first(indexes, "NAME", "COLNAME", "COLUMN_NAME"),
                    first(indexes, "COLTYPE", "DATA_TYPE"),
                    first(indexes, "LENGTH", "CHARACTER_MAXIMUM_LENGTH"),
                    first(indexes, "LENGTH2"),
                    first(indexes, "SCALE", "NUMERIC_SCALE"),
                    first(indexes, "TYPENAME", "UDT_NAME"));
        }

        boolean usable() {
            return schema >= 0 && table >= 0 && column >= 0 && type >= 0;
        }

        private static int first(Map<String, Integer> indexes, String... names) {
            for (String name : names) {
                Integer index = indexes.get(name);
                if (index != null) return index;
            }
            return -1;
        }

        private static String normalizeHeader(String value) {
            String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            return source.replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
        }
    }
}
