package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compares design-time physical options with actual database physical metadata.
 *
 * <p>The comparator is intentionally read-only. Database metadata remains on the
 * database-side canonical table and is never promoted into design intent.</p>
 */
public final class PhysicalMetadataComparator {
    private static final String REVIEW_PREFIX = "REVIEW:";

    public List<PhysicalComparisonRow> compareTable(
            Table expectedTable, Table actualTable, String databaseType) {
        Objects.requireNonNull(expectedTable, "expectedTable must not be null");
        Objects.requireNonNull(actualTable, "actualTable must not be null");
        String platform = normalizePlatform(databaseType);
        List<PhysicalComparisonRow> rows = new ArrayList<>();
        compareOptionMaps(rows, "TABLE", expectedTable.qualifiedName().toString(),
                expectedTable.physicalOptions(), actualTable.physicalOptions(),
                tableProperties(platform), platform, "");
        return List.copyOf(rows);
    }

    /**
     * Compares column-scoped persistent physical state. P8-C currently has
     * comparable column physical metadata only for PostgreSQL STORAGE and
     * COMPRESSION. Columns are matched by technical name; no rename is guessed.
     */
    public List<PhysicalComparisonRow> compareColumns(
            Table expectedTable, Table actualTable, String databaseType) {
        Objects.requireNonNull(expectedTable, "expectedTable must not be null");
        Objects.requireNonNull(actualTable, "actualTable must not be null");
        String platform = normalizePlatform(databaseType);
        if (!"POSTGRESQL".equals(platform)) return List.of();

        List<PhysicalComparisonRow> rows = new ArrayList<>();
        List<Column> remainingActual = new ArrayList<>(actualTable.columns());
        for (Column expected : expectedTable.columns()) {
            Column actual = findColumnByName(expected.name().value(), remainingActual);
            if (actual != null) remainingActual.remove(actual);
            comparePostgreSqlColumn(rows, expected, actual);
        }
        for (Column actual : remainingActual) {
            comparePostgreSqlColumn(rows, null, actual);
        }
        return List.copyOf(rows);
    }

    /**
     * Compares persistent physical state for ordinary indexes and the backing
     * indexes of PRIMARY KEY / UNIQUE constraints.
     */
    public List<PhysicalComparisonRow> compareIndexes(
            Table expectedTable, Table actualTable, String databaseType) {
        Objects.requireNonNull(expectedTable, "expectedTable must not be null");
        Objects.requireNonNull(actualTable, "actualTable must not be null");
        String platform = normalizePlatform(databaseType);
        List<PropertySpec> specs = indexProperties(platform);
        List<PhysicalComparisonRow> rows = new ArrayList<>();

        comparePrimaryKey(rows, expectedTable, actualTable, specs, platform);
        compareUniqueKeys(rows, expectedTable, actualTable, specs, platform);
        compareOrdinaryIndexes(rows, expectedTable, actualTable, specs, platform);
        return List.copyOf(rows);
    }

    private static void comparePrimaryKey(
            List<PhysicalComparisonRow> rows, Table expectedTable, Table actualTable,
            List<PropertySpec> specs, String platform) {
        PrimaryKey expected = expectedTable.primaryKey().orElse(null);
        PrimaryKey actual = actualTable.primaryKey().orElse(null);
        if (expected == null && actual == null) return;
        String expectedName = expected == null || expected.name() == null ? "PRIMARY_KEY" : expected.name().value();
        String actualName = actual == null || actual.name() == null ? "PRIMARY_KEY" : actual.name().value();
        compareOptionMaps(rows, "PRIMARY_KEY", objectLabel(expectedName, actualName),
                expected == null ? Map.of() : expected.physicalOptions(),
                actual == null ? Map.of() : actual.physicalOptions(),
                specs, platform, objectMatchNote(expectedName, actualName));
    }

    private static void compareUniqueKeys(
            List<PhysicalComparisonRow> rows, Table expectedTable, Table actualTable,
            List<PropertySpec> specs, String platform) {
        List<UniquePhysicalObject> expected = expectedTable.uniqueKeys().stream()
                .map(PhysicalMetadataComparator::uniqueObject).toList();
        List<UniquePhysicalObject> actual = new ArrayList<>(actualTable.uniqueKeys().stream()
                .map(PhysicalMetadataComparator::uniqueObject).toList());
        for (UniquePhysicalObject item : expected) {
            UniquePhysicalObject match = findMatch(item.name(), item.structuralKey(), actual);
            if (match != null) actual.remove(match);
            compareOptionMaps(rows, "UNIQUE_KEY",
                    objectLabel(item.name(), match == null ? null : match.name()),
                    item.options(), match == null ? Map.of() : match.options(), specs, platform,
                    match == null ? "Backing unique key/index was not found in database metadata."
                            : objectMatchNote(item.name(), match.name()));
        }
        for (UniquePhysicalObject item : actual) {
            compareOptionMaps(rows, "UNIQUE_KEY", objectLabel(null, item.name()),
                    Map.of(), item.options(), specs, platform,
                    "Database unique key/backing index has no matching design object.");
        }
    }

    private static void compareOrdinaryIndexes(
            List<PhysicalComparisonRow> rows, Table expectedTable, Table actualTable,
            List<PropertySpec> specs, String platform) {
        List<IndexPhysicalObject> expected = expectedTable.indexes().stream()
                .map(PhysicalMetadataComparator::indexObject).toList();
        List<IndexPhysicalObject> actual = new ArrayList<>(actualTable.indexes().stream()
                .map(PhysicalMetadataComparator::indexObject).toList());
        for (IndexPhysicalObject item : expected) {
            IndexPhysicalObject match = findIndexMatch(item.name(), item.structuralKey(), actual);
            if (match != null) actual.remove(match);
            compareOptionMaps(rows, "INDEX",
                    objectLabel(item.name(), match == null ? null : match.name()),
                    item.options(), match == null ? Map.of() : match.options(), specs, platform,
                    match == null ? "Index was not found in database metadata."
                            : objectMatchNote(item.name(), match.name()));
        }
        for (IndexPhysicalObject item : actual) {
            compareOptionMaps(rows, "INDEX", objectLabel(null, item.name()),
                    Map.of(), item.options(), specs, platform,
                    "Database index has no matching design object.");
        }
    }

    private static Column findColumnByName(String name, List<Column> candidates) {
        if (name == null) return null;
        return candidates.stream()
                .filter(column -> column.name().value().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static void comparePostgreSqlColumn(
            List<PhysicalComparisonRow> rows, Column expected, Column actual) {
        String expectedName = expected == null ? null : expected.name().value();
        String actualName = actual == null ? null : actual.name().value();
        String objectName = objectLabel(expectedName, actualName);
        String objectNote = expected == null
                ? "Database column has no matching design column."
                : actual == null ? "Column was not found in database metadata." : "";
        Map<String, String> expectedOptions = expected == null ? Map.of() : expected.physicalOptions();
        Map<String, String> actualOptions = actual == null ? Map.of() : actual.physicalOptions();

        comparePostgreSqlColumnProperty(rows, objectName, "STORAGE",
                List.of("POSTGRESQL_STORAGE", "COLUMN_STORAGE", "STORAGE"),
                expectedOptions, actualOptions, objectNote);
        comparePostgreSqlColumnProperty(rows, objectName, "COMPRESSION",
                List.of("POSTGRESQL_COMPRESSION", "COLUMN_COMPRESSION", "COMPRESSION"),
                expectedOptions, actualOptions, objectNote);
    }

    private static void comparePostgreSqlColumnProperty(
            List<PhysicalComparisonRow> rows, String objectName, String property, List<String> aliases,
            Map<String, String> expectedOptions, Map<String, String> actualOptions, String objectNote) {
        String expected = find(expectedOptions, aliases);
        String actual = find(actualOptions, aliases);
        if (expected == null && actual == null) return;

        boolean review = actual != null && actual.startsWith(REVIEW_PREFIX);
        String actualDisplay = review ? actual.substring(REVIEW_PREFIX.length()).trim() : actual;
        PhysicalComparisonStatus status;
        String note = objectNote == null ? "" : objectNote;
        if (review) {
            status = PhysicalComparisonStatus.REVIEW;
            note = appendNote(note, "Actual PostgreSQL catalog value is not recognized by the current mapping.");
        } else if (expected == null) {
            status = PhysicalComparisonStatus.NOT_SPECIFIED;
            note = appendNote(note,
                    "No design/profile value is specified; actual database metadata is shown for review.");
        } else if (actual == null) {
            status = PhysicalComparisonStatus.NOT_AVAILABLE;
            if ("COMPRESSION".equals(property)) {
                String storage = find(actualOptions, List.of("POSTGRESQL_STORAGE", "COLUMN_STORAGE", "STORAGE"));
                if ("PLAIN".equalsIgnoreCase(storage) || "EXTERNAL".equalsIgnoreCase(storage)) {
                    note = appendNote(note,
                            "PostgreSQL ignores column compression when STORAGE is PLAIN or EXTERNAL.");
                } else {
                    note = appendNote(note,
                            "The expected value is specified, but a comparable persistent value is not available from pg_attribute.");
                }
            } else {
                note = appendNote(note,
                        "The expected value is specified, but a comparable persistent value is not available from pg_attribute.");
            }
        } else if ("STORAGE".equals(property) && "DEFAULT".equalsIgnoreCase(expected)) {
            String typeDefault = find(actualOptions, List.of("POSTGRESQL_STORAGE_TYPE_DEFAULT"));
            if (typeDefault != null && normalizeValue(typeDefault).equals(normalizeValue(actualDisplay))) {
                status = PhysicalComparisonStatus.MATCH;
                note = appendNote(note,
                        "Actual effective storage equals the data type default (" + actualDisplay + ").");
            } else if (typeDefault == null) {
                status = PhysicalComparisonStatus.NOT_AVAILABLE;
                note = appendNote(note,
                        "Design requests STORAGE DEFAULT, but the data type default storage mode was not available from catalog metadata.");
            } else {
                status = PhysicalComparisonStatus.MISMATCH;
                note = appendNote(note, "Data type default storage is " + typeDefault + ".");
            }
        } else if (equivalent(expected, actualDisplay, "POSTGRESQL", false, property)) {
            status = PhysicalComparisonStatus.MATCH;
        } else {
            status = PhysicalComparisonStatus.MISMATCH;
        }

        rows.add(new PhysicalComparisonRow(
                "COLUMN", objectName, property, valueOrBlank(expected), valueOrBlank(actualDisplay), status, note));
    }

    private static void compareOptionMaps(
            List<PhysicalComparisonRow> rows, String scope, String objectName,
            Map<String, String> expectedOptions, Map<String, String> actualOptions,
            List<PropertySpec> specs, String platform, String objectNote) {
        for (PropertySpec spec : specs) {
            String expected = find(expectedOptions, spec.aliases());
            String actual = find(actualOptions, spec.aliases());
            if (expected == null && actual == null) continue;

            boolean review = actual != null && actual.startsWith(REVIEW_PREFIX);
            String actualDisplay = review ? actual.substring(REVIEW_PREFIX.length()).trim() : actual;
            PhysicalComparisonStatus status;
            String note = objectNote == null ? "" : objectNote;
            if (review) {
                status = PhysicalComparisonStatus.REVIEW;
                note = appendNote(note,
                        "Actual database state is mixed, version-dependent, or cannot be represented as one object-level value.");
            } else if (expected == null) {
                status = PhysicalComparisonStatus.NOT_SPECIFIED;
                note = appendNote(note,
                        "No design/profile value is specified; actual database metadata is shown for review.");
            } else if (actual == null) {
                status = PhysicalComparisonStatus.NOT_AVAILABLE;
                note = appendNote(note,
                        "The expected value is specified, but a comparable persistent value is not available from the current catalog mapping.");
            } else if (equivalent(expected, actualDisplay, platform, spec.identifierLike(), spec.property())) {
                status = PhysicalComparisonStatus.MATCH;
            } else {
                status = PhysicalComparisonStatus.MISMATCH;
            }

            rows.add(new PhysicalComparisonRow(
                    scope, objectName, spec.property(), valueOrBlank(expected), valueOrBlank(actualDisplay), status, note));
        }
    }

    private static List<PropertySpec> tableProperties(String platform) {
        return switch (platform) {
            case "ORACLE" -> List.of(
                    identifier("TABLESPACE", "TABLESPACE"),
                    property("PCTFREE", "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE"),
                    property("PCTUSED", "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED"),
                    property("INITRANS", "ORACLE_INITRANS", "TABLE_INITRANS", "INITRANS"),
                    property("COMPRESSION", "ORACLE_TABLE_COMPRESSION", "TABLE_COMPRESSION"),
                    property("LOGGING", "ORACLE_TABLE_LOGGING", "TABLE_LOGGING", "ORACLE_LOGGING"),
                    property("PARALLEL", "ORACLE_TABLE_PARALLEL", "TABLE_PARALLEL", "ORACLE_PARALLEL"),
                    property("SEGMENT_CREATION", "ORACLE_TABLE_SEGMENT_CREATION", "ORACLE_SEGMENT_CREATION", "SEGMENT_CREATION"));
            case "POSTGRESQL" -> List.of(
                    identifier("TABLESPACE", "TABLESPACE"),
                    property("FILLFACTOR", "POSTGRESQL_TABLE_FILLFACTOR", "TABLE_FILLFACTOR"),
                    property("TOAST_TUPLE_TARGET", "POSTGRESQL_TOAST_TUPLE_TARGET", "TABLE_TOAST_TUPLE_TARGET", "TOAST_TUPLE_TARGET"),
                    property("PARALLEL_WORKERS", "POSTGRESQL_TABLE_PARALLEL_WORKERS", "TABLE_PARALLEL_WORKERS", "PARALLEL_WORKERS"));
            case "SQLSERVER" -> List.of(
                    identifier("FILEGROUP_OR_DATA_SPACE", "TABLESPACE"),
                    property("DATA_COMPRESSION", "SQLSERVER_TABLE_DATA_COMPRESSION", "TABLE_DATA_COMPRESSION"),
                    property("XML_COMPRESSION", "SQLSERVER_TABLE_XML_COMPRESSION", "TABLE_XML_COMPRESSION"));
            case "MYSQL" -> List.of(
                    property("ENGINE", "MYSQL_ENGINE", "ENGINE"),
                    property("COLLATION", "MYSQL_COLLATION", "TABLE_COLLATION", "COLLATION"),
                    property("ROW_FORMAT", "MYSQL_ROW_FORMAT", "ROW_FORMAT"),
                    identifier("TABLESPACE", "MYSQL_TABLESPACE", "TABLESPACE"));
            case "DB2_LUW" -> List.of(
                    identifier("TABLESPACE", "TABLESPACE"),
                    identifier("INDEX_TABLESPACE", "DB2_LUW_INDEX_TABLESPACE", "TABLE_INDEX_TABLESPACE"),
                    identifier("LONG_TABLESPACE", "DB2_LUW_LONG_TABLESPACE", "TABLE_LONG_TABLESPACE"),
                    property("PCTFREE", "DB2_LUW_TABLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE"),
                    property("APPEND_MODE", "DB2_LUW_APPEND", "TABLE_APPEND", "APPEND"),
                    property("VOLATILE", "DB2_LUW_VOLATILE", "TABLE_VOLATILE", "VOLATILE"),
                    property("ORGANIZATION", "DB2_LUW_TABLE_ORGANIZATION", "TABLE_ORGANIZATION"),
                    property("ROW_COMPRESSION", "DB2_LUW_ROW_COMPRESSION", "TABLE_ROW_COMPRESSION"),
                    property("VALUE_COMPRESSION", "DB2_LUW_VALUE_COMPRESSION", "TABLE_VALUE_COMPRESSION"));
            case "DB2_ZOS" -> List.of(
                    identifier("DATABASE_TABLESPACE", "TABLESPACE"),
                    identifier("BUFFERPOOL", "DB2_TABLESPACE_BUFFERPOOL", "TABLESPACE_BUFFERPOOL", "DB2_BUFFERPOOL"),
                    property("DSSIZE", "DB2_TABLESPACE_DSSIZE", "TABLESPACE_DSSIZE"),
                    property("SEGSIZE", "DB2_TABLESPACE_SEGSIZE", "TABLESPACE_SEGSIZE"),
                    property("FREEPAGE", "DB2_TABLESPACE_FREEPAGE", "TABLESPACE_FREEPAGE"),
                    property("PCTFREE", "DB2_TABLESPACE_PCTFREE", "TABLESPACE_PCTFREE"),
                    property("PCTFREE_FOR_UPDATE", "DB2_TABLESPACE_PCTFREE_FOR_UPDATE", "TABLESPACE_PCTFREE_FOR_UPDATE"),
                    property("COMPRESS", "DB2_TABLESPACE_COMPRESS", "TABLESPACE_COMPRESS"),
                    property("GBPCACHE", "DB2_TABLESPACE_GBPCACHE", "TABLESPACE_GBPCACHE"),
                    property("CLOSE", "DB2_TABLESPACE_CLOSE", "TABLESPACE_CLOSE"),
                    property("DEFINE", "DB2_TABLESPACE_DEFINE", "TABLESPACE_DEFINE"),
                    property("LOCKSIZE", "DB2_TABLESPACE_LOCKSIZE", "TABLESPACE_LOCKSIZE"),
                    property("LOCKMAX", "DB2_TABLESPACE_LOCKMAX", "TABLESPACE_LOCKMAX"),
                    property("MAXROWS", "DB2_TABLESPACE_MAXROWS", "TABLESPACE_MAXROWS"),
                    property("MEMBER_CLUSTER", "DB2_TABLESPACE_MEMBER_CLUSTER", "TABLESPACE_MEMBER_CLUSTER"),
                    property("INSERT_ALGORITHM", "DB2_TABLESPACE_INSERT_ALGORITHM", "TABLESPACE_INSERT_ALGORITHM"),
                    property("TRACKMOD", "DB2_TABLESPACE_TRACKMOD", "TABLESPACE_TRACKMOD"),
                    property("LOGGING", "DB2_TABLESPACE_LOGGING", "TABLESPACE_LOGGING"),
                    identifier("STOGROUP", "DB2_TABLESPACE_STOGROUP", "TABLESPACE_STOGROUP"),
                    property("PRIQTY", "DB2_TABLESPACE_PRIQTY", "TABLESPACE_PRIQTY"),
                    property("SECQTY", "DB2_TABLESPACE_SECQTY", "TABLESPACE_SECQTY"),
                    property("ERASE", "DB2_TABLESPACE_ERASE", "TABLESPACE_ERASE"));
            default -> List.of(identifier("TABLESPACE", "TABLESPACE"));
        };
    }

    private static List<PropertySpec> indexProperties(String platform) {
        return switch (platform) {
            case "ORACLE" -> List.of(
                    identifier("TABLESPACE", "INDEX_TABLESPACE", "TABLESPACE"),
                    property("PCTFREE", "ORACLE_INDEX_PCTFREE", "INDEX_PCTFREE"),
                    property("INITRANS", "ORACLE_INDEX_INITRANS", "INDEX_INITRANS"),
                    property("COMPRESSION", "ORACLE_INDEX_COMPRESSION", "INDEX_COMPRESSION"),
                    property("LOGGING", "ORACLE_INDEX_LOGGING", "INDEX_LOGGING"),
                    property("PARALLEL", "ORACLE_INDEX_PARALLEL", "INDEX_PARALLEL"));
            case "POSTGRESQL" -> List.of(
                    identifier("TABLESPACE", "INDEX_TABLESPACE"),
                    property("ACCESS_METHOD", "POSTGRESQL_INDEX_METHOD", "INDEX_METHOD", "INDEX_ACCESS_METHOD"),
                    property("FILLFACTOR", "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR"),
                    property("DEDUPLICATE_ITEMS", "POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "INDEX_DEDUPLICATE_ITEMS"),
                    property("GIST_BUFFERING", "POSTGRESQL_GIST_BUFFERING", "GIST_BUFFERING"),
                    property("GIN_FASTUPDATE", "POSTGRESQL_GIN_FASTUPDATE", "GIN_FASTUPDATE"),
                    property("GIN_PENDING_LIST_LIMIT", "POSTGRESQL_GIN_PENDING_LIST_LIMIT", "GIN_PENDING_LIST_LIMIT"),
                    property("BRIN_PAGES_PER_RANGE", "POSTGRESQL_BRIN_PAGES_PER_RANGE", "BRIN_PAGES_PER_RANGE"),
                    property("BRIN_AUTOSUMMARIZE", "POSTGRESQL_BRIN_AUTOSUMMARIZE", "BRIN_AUTOSUMMARIZE"));
            case "SQLSERVER" -> List.of(
                    identifier("FILEGROUP_OR_DATA_SPACE", "INDEX_TABLESPACE"),
                    property("ORGANIZATION", "SQLSERVER_INDEX_ORGANIZATION", "INDEX_ORGANIZATION"),
                    property("FILLFACTOR", "SQLSERVER_INDEX_FILLFACTOR", "INDEX_FILLFACTOR"),
                    property("PAD_INDEX", "SQLSERVER_INDEX_PAD_INDEX", "INDEX_PAD_INDEX"),
                    property("DATA_COMPRESSION", "SQLSERVER_INDEX_DATA_COMPRESSION", "INDEX_DATA_COMPRESSION"),
                    property("IGNORE_DUP_KEY", "SQLSERVER_INDEX_IGNORE_DUP_KEY", "INDEX_IGNORE_DUP_KEY"),
                    property("STATISTICS_NORECOMPUTE", "SQLSERVER_INDEX_STATISTICS_NORECOMPUTE", "INDEX_STATISTICS_NORECOMPUTE"),
                    property("STATISTICS_INCREMENTAL", "SQLSERVER_INDEX_STATISTICS_INCREMENTAL", "INDEX_STATISTICS_INCREMENTAL"),
                    property("ALLOW_ROW_LOCKS", "SQLSERVER_INDEX_ALLOW_ROW_LOCKS", "INDEX_ALLOW_ROW_LOCKS"),
                    property("ALLOW_PAGE_LOCKS", "SQLSERVER_INDEX_ALLOW_PAGE_LOCKS", "INDEX_ALLOW_PAGE_LOCKS"),
                    property("XML_COMPRESSION", "SQLSERVER_INDEX_XML_COMPRESSION", "INDEX_XML_COMPRESSION"),
                    property("OPTIMIZE_FOR_SEQUENTIAL_KEY", "SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY", "INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY"));
            case "MYSQL" -> List.of(
                    property("ACCESS_METHOD", "MYSQL_INDEX_TYPE", "INDEX_TYPE", "INDEX_ACCESS_METHOD"));
            case "DB2_LUW" -> List.of(
                    identifier("TABLESPACE", "INDEX_TABLESPACE"),
                    property("PCTFREE", "DB2_LUW_INDEX_PCTFREE", "INDEX_PCTFREE"),
                    property("MINPCTUSED", "DB2_LUW_INDEX_MINPCTUSED", "INDEX_MINPCTUSED"),
                    property("REVERSE_SCANS", "DB2_LUW_INDEX_REVERSE_SCANS", "INDEX_REVERSE_SCANS"),
                    property("COMPRESSION", "DB2_LUW_INDEX_COMPRESSION", "INDEX_COMPRESSION"),
                    property("PAGE_SPLIT", "DB2_LUW_INDEX_PAGE_SPLIT", "INDEX_PAGE_SPLIT"));
            case "DB2_ZOS" -> List.of(
                    property("PADDING", "DB2_INDEX_PADDING", "INDEX_PADDING"),
                    identifier("STOGROUP", "DB2_INDEX_STOGROUP", "INDEX_STOGROUP"),
                    property("ERASE", "DB2_INDEX_ERASE", "INDEX_ERASE"),
                    property("FREEPAGE", "DB2_INDEX_FREEPAGE", "INDEX_FREEPAGE"),
                    property("PCTFREE", "DB2_INDEX_PCTFREE", "INDEX_PCTFREE"),
                    property("GBPCACHE", "DB2_INDEX_GBPCACHE", "INDEX_GBPCACHE"),
                    property("COMPRESS", "DB2_INDEX_COMPRESS", "INDEX_COMPRESS"),
                    identifier("BUFFERPOOL", "DB2_INDEX_BUFFERPOOL", "INDEX_BUFFERPOOL"),
                    property("CLOSE", "DB2_INDEX_CLOSE", "INDEX_CLOSE"),
                    property("PIECESIZE", "DB2_INDEX_PIECESIZE", "INDEX_PIECESIZE"));
            default -> List.of(identifier("TABLESPACE", "INDEX_TABLESPACE"));
        };
    }

    private static UniquePhysicalObject uniqueObject(UniqueKey key) {
        String name = key.name() == null ? "" : key.name().value();
        return new UniquePhysicalObject(name, identifierSignature(key.columns()), key.physicalOptions());
    }

    private static IndexPhysicalObject indexObject(Index index) {
        String name = index.name() == null ? "" : index.name().value();
        String keys = index.columns().stream().map(PhysicalMetadataComparator::indexColumnSignature)
                .reduce((left, right) -> left + "," + right).orElse("");
        String include = identifierSignature(index.includeColumns());
        String predicate = normalizeValue(index.predicate());
        String structural = index.type().name() + "|" + keys + "|" + include + "|" + predicate;
        return new IndexPhysicalObject(name, structural, index.physicalOptions());
    }

    private static String indexColumnSignature(IndexColumn column) {
        String value = column.expressionBased() ? normalizeValue(column.expression())
                : normalizeValue(column.column().value());
        return value + ":" + column.direction().name();
    }

    private static String identifierSignature(List<Identifier> identifiers) {
        return identifiers.stream().map(Identifier::value).map(PhysicalMetadataComparator::normalizeValue)
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    private static <T extends NamedPhysicalObject> T findMatch(String name, String structuralKey, List<T> candidates) {
        T byName = candidates.stream().filter(item -> !name.isBlank())
                .filter(item -> name.equalsIgnoreCase(item.name())).findFirst().orElse(null);
        if (byName != null) return byName;
        return candidates.stream().filter(item -> structuralKey.equals(item.structuralKey())).findFirst().orElse(null);
    }

    private static IndexPhysicalObject findIndexMatch(
            String name, String structuralKey, List<IndexPhysicalObject> candidates) {
        return findMatch(name, structuralKey, candidates);
    }

    private static String objectLabel(String expectedName, String actualName) {
        String expected = trimToNull(expectedName);
        String actual = trimToNull(actualName);
        if (expected == null) return actual == null ? "" : actual;
        if (actual == null || expected.equalsIgnoreCase(actual)) return expected;
        return expected + " <-> " + actual;
    }

    private static String objectMatchNote(String expectedName, String actualName) {
        String expected = trimToNull(expectedName);
        String actual = trimToNull(actualName);
        if (expected == null || actual == null || expected.equalsIgnoreCase(actual)) return "";
        return "Objects were matched structurally although their names differ.";
    }

    private static String appendNote(String base, String addition) {
        String left = trimToNull(base);
        String right = trimToNull(addition);
        if (left == null) return right == null ? "" : right;
        if (right == null) return left;
        return left + " " + right;
    }

    private static PropertySpec property(String name, String... aliases) {
        return new PropertySpec(name, List.of(aliases), false);
    }

    private static PropertySpec identifier(String name, String... aliases) {
        return new PropertySpec(name, List.of(aliases), true);
    }

    private static String find(Map<String, String> options, List<String> aliases) {
        if (options == null || options.isEmpty()) return null;
        for (String alias : aliases) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(alias)) {
                    String value = trimToNull(entry.getValue());
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private static boolean equivalent(
            String expected, String actual, String platform, boolean identifierLike, String property) {
        if ("SQLSERVER".equals(platform) && "FILLFACTOR".equals(property)) {
            String expectedNormalized = normalizeValue(expected);
            String actualNormalized = normalizeValue(actual);
            if (("0".equals(expectedNormalized) || "100".equals(expectedNormalized))
                    && ("0".equals(actualNormalized) || "100".equals(actualNormalized))) {
                return true;
            }
        }
        if (identifierLike && "POSTGRESQL".equals(platform)) {
            return expected.trim().equals(actual.trim());
        }
        return normalizeValue(expected).equals(normalizeValue(actual));
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String normalizePlatform(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace('/', '_');
        if (normalized.equals("POSTGRES") || normalized.equals("POSTGRESQL")) return "POSTGRESQL";
        if (normalized.equals("SQL_SERVER") || normalized.equals("SQLSERVER")) return "SQLSERVER";
        if (normalized.equals("DB2") || normalized.equals("DB2ZOS") || normalized.equals("DB2_ZOS")) return "DB2_ZOS";
        if (normalized.equals("DB2LUW") || normalized.equals("DB2_LUW") || normalized.equals("LUW")) return "DB2_LUW";
        return normalized;
    }

    private static String valueOrBlank(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private interface NamedPhysicalObject {
        String name();
        String structuralKey();
        Map<String, String> options();
    }

    private record UniquePhysicalObject(String name, String structuralKey, Map<String, String> options)
            implements NamedPhysicalObject { }

    private record IndexPhysicalObject(String name, String structuralKey, Map<String, String> options)
            implements NamedPhysicalObject { }

    private record PropertySpec(String property, List<String> aliases, boolean identifierLike) { }
}
