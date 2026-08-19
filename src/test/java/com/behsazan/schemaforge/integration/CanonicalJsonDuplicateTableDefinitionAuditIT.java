package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audits historical duplicate table definitions in a canonical JSON corpus.
 *
 * <p>The batch diagram exporters intentionally exclude every qualified table name that has more
 * than one definition, because choosing one historical definition would violate SchemaForge's
 * no-guess policy. This test-only audit explains those duplicates instead of selecting a winner.
 * It groups all persisted table definitions by qualified name, compares their database-neutral
 * logical signatures, and reports which groups are exact logical duplicates versus genuinely
 * conflicting definitions.</p>
 *
 * <p>No source document is reopened, no database is contacted, and no production behavior is
 * changed by this runner. The output is evidence for a later, separately reviewed decision about
 * whether exact duplicate definitions may be collapsed for batch ERD generation.</p>
 */
class CanonicalJsonDuplicateTableDefinitionAuditIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.duplicates.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.duplicates.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.snapshot.duplicates.cleanOutput";
    private static final String FAIL_ON_ERRORS = "schemaforge.snapshot.duplicates.failOnErrors";

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();

    @Test
    void auditsDuplicateTableDefinitionsAcrossCanonicalJsonCorpus() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "false"));
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));

        validateNonOverlapping(inputRoot, outputRoot);
        if (cleanOutput) {
            cleanDirectory(outputRoot);
        }
        Files.createDirectories(outputRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonDuplicateTableDefinitionAuditIT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertTrue(!snapshots.isEmpty(), "No *.schema.json snapshots found under " + inputRoot);

        Map<String, List<Definition>> byTable = new TreeMap<>();
        List<String> snapshotFailures = new ArrayList<>();
        snapshotFailures.add("snapshot,source,error");

        int processedSnapshots = 0;
        int staleParserSnapshots = 0;
        int tableDefinitions = 0;

        for (Path snapshotPath : snapshots) {
            String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
            String source = "";
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                source = snapshot.source() == null ? "" : nullToEmpty(snapshot.source().relativePath());
                if (!CanonicalSnapshotVersions.parserCurrent(snapshot)) {
                    staleParserSnapshots++;
                }
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                processedSnapshots++;
                for (Table table : schema.tables()) {
                    tableDefinitions++;
                    Definition definition = new Definition(
                            table,
                            relativeSnapshot,
                            source,
                            snapshot.source() == null ? "" : nullToEmpty(snapshot.source().sha256()));
                    byTable.computeIfAbsent(tableKey(table), ignored -> new ArrayList<>()).add(definition);
                }
            } catch (Exception exception) {
                snapshotFailures.add(csvLine(
                        relativeSnapshot,
                        source,
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            }
        }

        List<GroupAnalysis> duplicateGroups = byTable.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> analyzeGroup(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(GroupAnalysis::tableKey))
                .toList();

        String timestamp = outputFileNamer.timestamp();
        Path summaryPath = outputRoot.resolve("duplicate-table-definition-summary_" + timestamp + ".txt");
        Path groupsPath = outputRoot.resolve("duplicate-table-definition-groups_" + timestamp + ".csv");
        Path membersPath = outputRoot.resolve("duplicate-table-definition-members_" + timestamp + ".csv");
        Path variantsPath = outputRoot.resolve("duplicate-table-definition-variants_" + timestamp + ".csv");
        Path failuresPath = outputRoot.resolve("duplicate-table-definition-snapshot-failures_" + timestamp + ".csv");

        writeGroups(groupsPath, duplicateGroups);
        writeMembers(membersPath, duplicateGroups);
        writeVariants(variantsPath, duplicateGroups);
        Files.write(failuresPath, snapshotFailures, StandardCharsets.UTF_8);

        int distinctTableNames = byTable.size();
        int uniqueTableNames = (int) byTable.values().stream().filter(values -> values.size() == 1).count();
        int duplicateTableNames = duplicateGroups.size();
        int definitionsInsideDuplicateGroups = duplicateGroups.stream().mapToInt(GroupAnalysis::occurrences).sum();
        int extraDuplicateOccurrences = definitionsInsideDuplicateGroups - duplicateTableNames;

        Map<Classification, Integer> classificationCounts = new LinkedHashMap<>();
        for (Classification classification : Classification.values()) {
            classificationCounts.put(classification, 0);
        }
        duplicateGroups.forEach(group -> classificationCounts.computeIfPresent(
                group.classification(), (ignored, value) -> value + 1));

        int exactLogicalGroups = classificationCounts.get(Classification.EXACT_LOGICAL_DUPLICATE);
        int sameStructureGroups = classificationCounts.get(Classification.SAME_STRUCTURE_DIFFERENT_SOURCE);
        int exactLogicalDefinitions = duplicateGroups.stream()
                .filter(group -> group.classification() == Classification.EXACT_LOGICAL_DUPLICATE)
                .mapToInt(GroupAnalysis::occurrences)
                .sum();
        int projectedBatchTableCountIfExactCollapsed = uniqueTableNames + exactLogicalGroups;

        String summary = summary(
                inputRoot,
                snapshots.size(),
                processedSnapshots,
                snapshotFailures.size() - 1,
                staleParserSnapshots,
                tableDefinitions,
                distinctTableNames,
                uniqueTableNames,
                duplicateTableNames,
                definitionsInsideDuplicateGroups,
                extraDuplicateOccurrences,
                classificationCounts,
                exactLogicalDefinitions,
                projectedBatchTableCountIfExactCollapsed,
                summaryPath,
                groupsPath,
                membersPath,
                variantsPath,
                failuresPath);
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        assertTrue(processedSnapshots > 0, "No canonical snapshots were processed");
        assertTrue(duplicateTableNames > 0,
                "No duplicate table definitions were found; this runner is intended for a historical corpus");
        if (failOnErrors) {
            assertTrue(snapshotFailures.size() == 1,
                    "Snapshot failures were reported in " + failuresPath);
        }
    }

    private static GroupAnalysis analyzeGroup(String tableKey, List<Definition> definitions) {
        List<MemberAnalysis> members = definitions.stream()
                .map(CanonicalJsonDuplicateTableDefinitionAuditIT::analyzeMember)
                .sorted(Comparator
                        .comparing(MemberAnalysis::source)
                        .thenComparing(MemberAnalysis::snapshot))
                .toList();

        int exactLogicalVariants = distinctCount(members, MemberAnalysis::exactLogicalSignature);
        int structuralVariants = distinctCount(members, MemberAnalysis::structuralSignature);
        int columnSetVariants = distinctCount(members, MemberAnalysis::columnSetSignature);
        int datatypeVariants = distinctCount(members, MemberAnalysis::datatypeSignature);
        int columnPropertyVariants = distinctCount(members, MemberAnalysis::columnPropertySignature);
        int primaryKeyVariants = distinctCount(members, MemberAnalysis::primaryKeySignature);
        int foreignKeyVariants = distinctCount(members, MemberAnalysis::foreignKeySignature);
        int constraintVariants = distinctCount(members, MemberAnalysis::constraintSignature);

        Classification classification;
        if (exactLogicalVariants == 1) {
            classification = Classification.EXACT_LOGICAL_DUPLICATE;
        } else if (structuralVariants == 1) {
            classification = Classification.SAME_STRUCTURE_DIFFERENT_SOURCE;
        } else if (columnSetVariants > 1) {
            classification = Classification.DIFFERENT_COLUMNS;
        } else if (datatypeVariants > 1) {
            classification = Classification.DIFFERENT_DATATYPES;
        } else if (columnPropertyVariants > 1) {
            classification = Classification.DIFFERENT_COLUMN_PROPERTIES;
        } else if (primaryKeyVariants > 1) {
            classification = Classification.DIFFERENT_PK;
        } else if (foreignKeyVariants > 1) {
            classification = Classification.DIFFERENT_FK;
        } else {
            classification = Classification.DIFFERENT_CONSTRAINTS;
        }

        List<String> differenceFlags = new ArrayList<>();
        if (columnSetVariants > 1) differenceFlags.add("DIFFERENT_COLUMNS");
        if (datatypeVariants > 1) differenceFlags.add("DIFFERENT_DATATYPES");
        if (columnPropertyVariants > 1) differenceFlags.add("DIFFERENT_COLUMN_PROPERTIES");
        if (primaryKeyVariants > 1) differenceFlags.add("DIFFERENT_PK");
        if (foreignKeyVariants > 1) differenceFlags.add("DIFFERENT_FK");
        if (constraintVariants > 1) differenceFlags.add("DIFFERENT_CONSTRAINTS");
        if (exactLogicalVariants > 1 && structuralVariants == 1) {
            differenceFlags.add("DIFFERENT_LOGICAL_OBJECT_NAMES");
        }

        CollapseSafety collapseSafety = switch (classification) {
            case EXACT_LOGICAL_DUPLICATE -> CollapseSafety.SAFE_EXACT_LOGICAL;
            case SAME_STRUCTURE_DIFFERENT_SOURCE -> CollapseSafety.REVIEW_SAME_STRUCTURE;
            default -> CollapseSafety.UNSAFE_DIFFERENT_STRUCTURE;
        };

        return new GroupAnalysis(
                tableKey,
                members.size(),
                members.stream().map(MemberAnalysis::source).filter(value -> !value.isBlank()).distinct().count(),
                classification,
                collapseSafety,
                exactLogicalVariants,
                structuralVariants,
                columnSetVariants,
                datatypeVariants,
                columnPropertyVariants,
                primaryKeyVariants,
                foreignKeyVariants,
                constraintVariants,
                String.join(";", differenceFlags),
                members);
    }

    private static MemberAnalysis analyzeMember(Definition definition) {
        Table table = definition.table();
        String columnSet = columnSetSignature(table);
        String datatype = datatypeSignature(table);
        String columnProperties = columnPropertySignature(table);
        String primaryKey = primaryKeySignature(table, false);
        String foreignKey = foreignKeySignature(table, false);
        String constraints = constraintSignature(table, false);
        String structural = String.join("\n",
                "COLUMNS=" + columnSet,
                "DATATYPES=" + datatype,
                "COLUMN_PROPERTIES=" + columnProperties,
                "PK=" + primaryKey,
                "FK=" + foreignKey,
                "CONSTRAINTS=" + constraints);
        String exactLogical = String.join("\n",
                "COLUMNS=" + columnSet,
                "DATATYPES=" + datatype,
                "COLUMN_PROPERTIES=" + columnProperties,
                "PK=" + primaryKeySignature(table, true),
                "FK=" + foreignKeySignature(table, true),
                "CONSTRAINTS=" + constraintSignature(table, true));

        return new MemberAnalysis(
                definition.snapshot(),
                definition.source(),
                definition.sourceSha256(),
                table.columns().size(),
                table.foreignKeys().size(),
                table.uniqueKeys().size(),
                table.checkConstraints().size(),
                table.primaryKey().isPresent(),
                exactLogical,
                structural,
                columnSet,
                datatype,
                columnProperties,
                primaryKey,
                foreignKey,
                constraints);
    }

    private static String columnSetSignature(Table table) {
        return table.columns().stream()
                .map(column -> id(column.name()))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String datatypeSignature(Table table) {
        return table.columns().stream()
                .map(column -> id(column.name()) + "=" + dataTypeSignature(column.dataType()))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String columnPropertySignature(Table table) {
        return table.columns().stream()
                .map(column -> id(column.name())
                        + "{nullable=" + column.nullable()
                        + ",default=" + normalizedExpression(column.defaultValue().expression())
                        + ",identity=" + column.identity()
                        + ",generated=" + normalizedExpression(column.generatedExpression())
                        + "}")
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String dataTypeSignature(DataType type) {
        return id(type.name())
                + "(length=" + nullToEmpty(type.length())
                + ",semantics=" + type.lengthSemantics().name()
                + ",precision=" + nullToEmpty(type.precision())
                + ",scale=" + nullToEmpty(type.scale())
                + ")";
    }

    private static String primaryKeySignature(Table table, boolean includeName) {
        return table.primaryKey()
                .map(key -> primaryKeySignature(key, includeName))
                .orElse("<NONE>");
    }

    private static String primaryKeySignature(PrimaryKey key, boolean includeName) {
        return (includeName ? "name=" + nullableId(key.name()) + "," : "")
                + "columns=" + ids(key.columns())
                + ",deferrable=" + key.deferrable()
                + ",initiallyDeferred=" + key.initiallyDeferred();
    }

    private static String foreignKeySignature(Table table, boolean includeName) {
        return table.foreignKeys().stream()
                .map(key -> foreignKeySignature(key, includeName))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String foreignKeySignature(ForeignKey key, boolean includeName) {
        return (includeName ? "name=" + nullableId(key.name()) + "," : "")
                + "columns=" + ids(key.columns())
                + ",target=" + qualifiedName(key.referencedTable())
                + ",targetColumns=" + ids(key.referencedColumns())
                + ",onDelete=" + key.onDelete().name()
                + ",onUpdate=" + key.onUpdate().name()
                + ",deferrable=" + key.deferrable()
                + ",initiallyDeferred=" + key.initiallyDeferred()
                + ",physicalReference=" + key.physicalReference();
    }

    private static String constraintSignature(Table table, boolean includeName) {
        String uniqueKeys = table.uniqueKeys().stream()
                .map(key -> uniqueKeySignature(key, includeName))
                .sorted()
                .collect(Collectors.joining("|"));
        String checks = table.checkConstraints().stream()
                .map(check -> checkSignature(check, includeName))
                .sorted()
                .collect(Collectors.joining("|"));
        return "UK=[" + uniqueKeys + "];CHECK=[" + checks + "]";
    }

    private static String uniqueKeySignature(UniqueKey key, boolean includeName) {
        return (includeName ? "name=" + nullableId(key.name()) + "," : "")
                + "columns=" + ids(key.columns())
                + ",deferrable=" + key.deferrable()
                + ",initiallyDeferred=" + key.initiallyDeferred();
    }

    private static String checkSignature(CheckConstraint check, boolean includeName) {
        return (includeName ? "name=" + nullableId(check.name()) + "," : "")
                + "expression=" + normalizedExpression(check.expression());
    }

    private static void writeGroups(Path path, List<GroupAnalysis> groups) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("table,occurrences,source_count,classification,collapse_safety,exact_logical_variants,"
                + "structural_variants,column_set_variants,datatype_variants,column_property_variants,"
                + "primary_key_variants,foreign_key_variants,constraint_variants,difference_flags,sources");
        for (GroupAnalysis group : groups) {
            String sources = group.members().stream()
                    .map(MemberAnalysis::source)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(" | "));
            rows.add(csvLine(
                    group.tableKey(),
                    group.occurrences(),
                    group.sourceCount(),
                    group.classification().name(),
                    group.collapseSafety().name(),
                    group.exactLogicalVariants(),
                    group.structuralVariants(),
                    group.columnSetVariants(),
                    group.datatypeVariants(),
                    group.columnPropertyVariants(),
                    group.primaryKeyVariants(),
                    group.foreignKeyVariants(),
                    group.constraintVariants(),
                    group.differenceFlags(),
                    sources));
        }
        Files.write(path, rows, StandardCharsets.UTF_8);
    }

    private static void writeMembers(Path path, List<GroupAnalysis> groups) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("table,classification,collapse_safety,snapshot,source,source_sha256,column_count,foreign_key_count,"
                + "unique_key_count,check_count,has_primary_key,exact_logical_sha256,structural_sha256,"
                + "column_set_sha256,datatype_sha256,column_property_sha256,primary_key_sha256,foreign_key_sha256,"
                + "constraint_sha256");
        for (GroupAnalysis group : groups) {
            for (MemberAnalysis member : group.members()) {
                rows.add(csvLine(
                        group.tableKey(),
                        group.classification().name(),
                        group.collapseSafety().name(),
                        member.snapshot(),
                        member.source(),
                        member.sourceSha256(),
                        member.columnCount(),
                        member.foreignKeyCount(),
                        member.uniqueKeyCount(),
                        member.checkCount(),
                        member.hasPrimaryKey(),
                        sha256(member.exactLogicalSignature()),
                        sha256(member.structuralSignature()),
                        sha256(member.columnSetSignature()),
                        sha256(member.datatypeSignature()),
                        sha256(member.columnPropertySignature()),
                        sha256(member.primaryKeySignature()),
                        sha256(member.foreignKeySignature()),
                        sha256(member.constraintSignature())));
            }
        }
        Files.write(path, rows, StandardCharsets.UTF_8);
    }

    private static void writeVariants(Path path, List<GroupAnalysis> groups) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("table,classification,dimension,variant_no,member_count,signature_sha256,signature_preview,sources");
        for (GroupAnalysis group : groups) {
            addVariants(rows, group, "EXACT_LOGICAL", MemberAnalysis::exactLogicalSignature, true);
            addVariants(rows, group, "STRUCTURE", MemberAnalysis::structuralSignature, true);
            addVariants(rows, group, "COLUMN_SET", MemberAnalysis::columnSetSignature, false);
            addVariants(rows, group, "DATATYPE", MemberAnalysis::datatypeSignature, false);
            addVariants(rows, group, "COLUMN_PROPERTIES", MemberAnalysis::columnPropertySignature, false);
            addVariants(rows, group, "PRIMARY_KEY", MemberAnalysis::primaryKeySignature, false);
            addVariants(rows, group, "FOREIGN_KEY", MemberAnalysis::foreignKeySignature, false);
            addVariants(rows, group, "CONSTRAINTS", MemberAnalysis::constraintSignature, false);
        }
        Files.write(path, rows, StandardCharsets.UTF_8);
    }

    private static void addVariants(
            List<String> rows,
            GroupAnalysis group,
            String dimension,
            Function<MemberAnalysis, String> extractor,
            boolean onlyWhenDifferent) {

        Map<String, List<MemberAnalysis>> variants = group.members().stream()
                .collect(Collectors.groupingBy(extractor, TreeMap::new, Collectors.toList()));
        if (onlyWhenDifferent && variants.size() <= 1) {
            return;
        }
        if (!onlyWhenDifferent && variants.size() <= 1) {
            return;
        }

        int variantNo = 0;
        for (Map.Entry<String, List<MemberAnalysis>> entry : variants.entrySet()) {
            variantNo++;
            String sources = entry.getValue().stream()
                    .map(MemberAnalysis::source)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(" | "));
            rows.add(csvLine(
                    group.tableKey(),
                    group.classification().name(),
                    dimension,
                    variantNo,
                    entry.getValue().size(),
                    sha256(entry.getKey()),
                    preview(entry.getKey()),
                    sources));
        }
    }

    private static String summary(
            Path inputRoot,
            int snapshotsDiscovered,
            int snapshotsProcessed,
            int snapshotFailures,
            int staleParserSnapshots,
            int tableDefinitions,
            int distinctTableNames,
            int uniqueTableNames,
            int duplicateTableNames,
            int definitionsInsideDuplicateGroups,
            int extraDuplicateOccurrences,
            Map<Classification, Integer> classificationCounts,
            int exactLogicalDefinitions,
            int projectedBatchTableCountIfExactCollapsed,
            Path summaryPath,
            Path groupsPath,
            Path membersPath,
            Path variantsPath,
            Path failuresPath) {

        StringBuilder text = new StringBuilder();
        text.append("SchemaForge duplicate table definition audit").append(System.lineSeparator());
        text.append("===========================================").append(System.lineSeparator());
        text.append("Input snapshots                 : ").append(inputRoot).append(System.lineSeparator());
        text.append("Snapshots discovered            : ").append(snapshotsDiscovered).append(System.lineSeparator());
        text.append("Snapshots processed             : ").append(snapshotsProcessed).append(System.lineSeparator());
        text.append("Snapshot failures               : ").append(snapshotFailures).append(System.lineSeparator());
        text.append("Stale parser sources            : ").append(staleParserSnapshots).append(System.lineSeparator());
        text.append("Table definitions               : ").append(tableDefinitions).append(System.lineSeparator());
        text.append("Distinct qualified table names  : ").append(distinctTableNames).append(System.lineSeparator());
        text.append("Unique table names              : ").append(uniqueTableNames).append(System.lineSeparator());
        text.append("Duplicate table names           : ").append(duplicateTableNames).append(System.lineSeparator());
        text.append("Definitions in duplicate groups : ").append(definitionsInsideDuplicateGroups).append(System.lineSeparator());
        text.append("Extra duplicate occurrences     : ").append(extraDuplicateOccurrences).append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("Classification counts").append(System.lineSeparator());
        text.append("---------------------").append(System.lineSeparator());
        classificationCounts.forEach((classification, count) -> text
                .append("  ").append(classification.name()).append(" = ").append(count)
                .append(System.lineSeparator()));
        text.append(System.lineSeparator());
        text.append("Collapse evidence for a future batch-ERD change").append(System.lineSeparator());
        text.append("-----------------------------------------------").append(System.lineSeparator());
        text.append("  Exact-logical duplicate definitions : ").append(exactLogicalDefinitions).append(System.lineSeparator());
        text.append("  Projected batch table count if ONLY exact-logical groups are collapsed : ")
                .append(projectedBatchTableCountIfExactCollapsed).append(System.lineSeparator());
        text.append("  SAME_STRUCTURE_DIFFERENT_SOURCE remains REVIEW because relationship/constraint labels may differ.")
                .append(System.lineSeparator());
        text.append("  Conflicting groups remain excluded; this audit never selects a historical winner.")
                .append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("Classification semantics").append(System.lineSeparator());
        text.append("------------------------").append(System.lineSeparator());
        text.append("  EXACT_LOGICAL_DUPLICATE          : same logical columns/properties and named PK/FK/UK/CHECK semantics; descriptions and physical/index metadata ignored.")
                .append(System.lineSeparator());
        text.append("  SAME_STRUCTURE_DIFFERENT_SOURCE  : same logical structure when constraint names are ignored, but exact logical signatures differ.")
                .append(System.lineSeparator());
        text.append("  DIFFERENT_COLUMNS                : column-name sets differ.").append(System.lineSeparator());
        text.append("  DIFFERENT_DATATYPES              : same column-name set but at least one canonical datatype differs.").append(System.lineSeparator());
        text.append("  DIFFERENT_COLUMN_PROPERTIES      : nullability/default/identity/generated semantics differ.").append(System.lineSeparator());
        text.append("  DIFFERENT_PK / DIFFERENT_FK      : key semantics differ.").append(System.lineSeparator());
        text.append("  DIFFERENT_CONSTRAINTS            : UK/CHECK semantics differ after the earlier dimensions match.").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("Reports").append(System.lineSeparator());
        text.append("-------").append(System.lineSeparator());
        text.append("Summary  : ").append(summaryPath).append(System.lineSeparator());
        text.append("Groups   : ").append(groupsPath).append(System.lineSeparator());
        text.append("Members  : ").append(membersPath).append(System.lineSeparator());
        text.append("Variants : ").append(variantsPath).append(System.lineSeparator());
        text.append("Failures : ").append(failuresPath).append(System.lineSeparator());
        return text.toString();
    }

    private static int distinctCount(List<MemberAnalysis> members, Function<MemberAnalysis, String> extractor) {
        return (int) members.stream().map(extractor).distinct().count();
    }

    private static String tableKey(Table table) {
        return qualifiedName(table.qualifiedName());
    }

    private static String qualifiedName(QualifiedName name) {
        return name.schemaName()
                .map(schema -> id(schema) + "." + id(name.name()))
                .orElseGet(() -> id(name.name()));
    }

    private static String ids(List<Identifier> identifiers) {
        return identifiers.stream().map(CanonicalJsonDuplicateTableDefinitionAuditIT::id)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String id(Identifier identifier) {
        return identifier.normalized();
    }

    private static String nullableId(Identifier identifier) {
        return identifier == null ? "" : id(identifier);
    }

    private static String normalizedExpression(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute SHA-256", exception);
        }
    }

    private static String preview(String value) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        int limit = 700;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static Path requiredDirectory(String property) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Missing required -D" + property + "=<directory>");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory does not exist: " + path);
        }
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String configured = System.getProperty(OUTPUT_DIR);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path parent = inputRoot.getParent() == null ? inputRoot : inputRoot.getParent();
        return parent.resolve(inputRoot.getFileName() + "-duplicate-table-audit").toAbsolutePath().normalize();
    }

    private static void validateNonOverlapping(Path inputRoot, Path outputRoot) {
        Path normalizedInput = inputRoot.toAbsolutePath().normalize();
        Path normalizedOutput = outputRoot.toAbsolutePath().normalize();
        if (normalizedInput.equals(normalizedOutput)
                || normalizedInput.startsWith(normalizedOutput)
                || normalizedOutput.startsWith(normalizedInput)) {
            throw new IllegalArgumentException(
                    "Input and output directories must not overlap: " + normalizedOutput);
        }
    }

    private static void cleanDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static boolean isSnapshot(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "" : exception.getMessage().replace('\n', ' ').replace('\r', ' ');
    }

    private static String csvLine(Object... values) {
        return java.util.Arrays.stream(values)
                .map(value -> csv(value == null ? "" : String.valueOf(value)))
                .collect(Collectors.joining(","));
    }

    private static String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private enum Classification {
        EXACT_LOGICAL_DUPLICATE,
        SAME_STRUCTURE_DIFFERENT_SOURCE,
        DIFFERENT_COLUMNS,
        DIFFERENT_DATATYPES,
        DIFFERENT_COLUMN_PROPERTIES,
        DIFFERENT_PK,
        DIFFERENT_FK,
        DIFFERENT_CONSTRAINTS
    }

    private enum CollapseSafety {
        SAFE_EXACT_LOGICAL,
        REVIEW_SAME_STRUCTURE,
        UNSAFE_DIFFERENT_STRUCTURE
    }

    private record Definition(Table table, String snapshot, String source, String sourceSha256) {
        private Definition {
            Objects.requireNonNull(table);
            snapshot = nullToEmpty(snapshot);
            source = nullToEmpty(source);
            sourceSha256 = nullToEmpty(sourceSha256);
        }
    }

    private record MemberAnalysis(
            String snapshot,
            String source,
            String sourceSha256,
            int columnCount,
            int foreignKeyCount,
            int uniqueKeyCount,
            int checkCount,
            boolean hasPrimaryKey,
            String exactLogicalSignature,
            String structuralSignature,
            String columnSetSignature,
            String datatypeSignature,
            String columnPropertySignature,
            String primaryKeySignature,
            String foreignKeySignature,
            String constraintSignature) {
    }

    private record GroupAnalysis(
            String tableKey,
            int occurrences,
            long sourceCount,
            Classification classification,
            CollapseSafety collapseSafety,
            int exactLogicalVariants,
            int structuralVariants,
            int columnSetVariants,
            int datatypeVariants,
            int columnPropertyVariants,
            int primaryKeyVariants,
            int foreignKeyVariants,
            int constraintVariants,
            String differenceFlags,
            List<MemberAnalysis> members) {
        private GroupAnalysis {
            members = List.copyOf(members);
        }
    }
}
