package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalCommentRendererResolver;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Physical Phase-1 corpus audit over persisted canonical JSON sources.
 *
 * <p>This runner deliberately does not validate datatype compatibility. Every snapshot is audited at
 * the canonical/physical-renderer level. Full DDL is additionally inspected when the dialect can render
 * it; a datatype or other non-physical generation exception is reported as DDL_UNAVAILABLE and does not
 * count as a physical violation.</p>
 */
class PhysicalPhase1CorpusAuditIT {
    private static final String INPUT_DIR = "schemaforge.physical.audit.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.physical.audit.outputDir";
    private static final String PLATFORMS = "schemaforge.physical.audit.platforms";
    private static final String FAIL_ON_VIOLATIONS = "schemaforge.physical.audit.failOnViolations";

    private static final List<DatabasePlatform> DEFAULT_PLATFORMS = List.of(
            DatabasePlatform.ORACLE, DatabasePlatform.POSTGRESQL,
            DatabasePlatform.SQLSERVER, DatabasePlatform.DB2_ZOS);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--.*$");
    private static final Pattern CHECK_CONSTRAINT = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final SqlScriptStatementParser statementParser = new SqlScriptStatementParser();

    @Test
    void auditsPhysicalPhase1AcrossCanonicalJsonCorpus() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        Files.createDirectories(outputRoot);
        List<DatabasePlatform> platforms = configuredPlatforms();
        boolean failOnViolations = Boolean.parseBoolean(System.getProperty(FAIL_ON_VIOLATIONS, "false"));

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("manifest.json"))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }

        String timestamp = TIMESTAMP.format(LocalDateTime.now());
        List<String> detail = new ArrayList<>();
        detail.add("snapshot,source,platform,status,tables,ddl_available,expected_table_blocks,actual_table_blocks,"
                + "expected_index_blocks,actual_index_blocks,expected_fk_recommendations,actual_fk_recommendations,"
                + "expected_padded_reviews,actual_padded_reviews,physical_violation_count,note");
        List<String> findings = new ArrayList<>();
        findings.add("snapshot,source,platform,severity,location,code,message,evidence");

        Map<DatabasePlatform, Stats> stats = new EnumMap<>(DatabasePlatform.class);
        platforms.forEach(platform -> stats.put(platform, new Stats()));

        int snapshotFailures = 0;
        for (Path snapshotPath : snapshots) {
            String relative = normalize(inputRoot.relativize(snapshotPath));
            CanonicalSchemaSnapshot snapshot;
            PreparedSchema prepared;
            String source = "";
            try {
                snapshot = store.readSnapshot(snapshotPath);
                source = snapshot.source() == null ? "" : safe(snapshot.source().relativePath());
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                prepared = preparationService.prepare(schema);
            } catch (Exception exception) {
                snapshotFailures++;
                for (DatabasePlatform platform : platforms) {
                    Stats s = stats.get(platform);
                    s.snapshots++;
                    s.physicalViolations++;
                    finding(findings, relative, source, platform, "ERROR", "SNAPSHOT",
                            "PHYS-SNAPSHOT-READ-001", "Snapshot could not be loaded for physical audit",
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                    detail.add(csvLine(relative, source, platform.commandLineName(), "SNAPSHOT_FAILED", "0", "false",
                            "0", "0", "0", "0", "0", "0", "0", "0", "1", safeMessage(exception)));
                }
                continue;
            }

            for (DatabasePlatform platform : platforms) {
                auditSnapshot(relative, source, prepared, platform, stats.get(platform), detail, findings);
            }
        }

        Path reportDir = Files.createDirectories(outputRoot.resolve("reports"));
        Path detailFile = reportDir.resolve("physical-phase1-audit-detail_" + timestamp + ".csv");
        Path findingsFile = reportDir.resolve("physical-phase1-audit-findings_" + timestamp + ".csv");
        Path summaryCsv = reportDir.resolve("physical-phase1-audit-summary_" + timestamp + ".csv");
        Path summaryTxt = reportDir.resolve("physical-phase1-audit-summary_" + timestamp + ".txt");

        Files.writeString(detailFile, String.join(System.lineSeparator(), detail) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(findingsFile, String.join(System.lineSeparator(), findings) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(summaryCsv, summaryCsv(platforms, stats), StandardCharsets.UTF_8);
        Files.writeString(summaryTxt, summaryText(inputRoot, snapshots.size(), snapshotFailures, platforms, stats),
                StandardCharsets.UTF_8);

        int totalViolations = stats.values().stream().mapToInt(value -> value.physicalViolations).sum();
        System.out.println("Physical Phase-1 corpus audit");
        System.out.println("Snapshots discovered : " + snapshots.size());
        System.out.println("Snapshot failures    : " + snapshotFailures);
        for (DatabasePlatform platform : platforms) {
            Stats s = stats.get(platform);
            System.out.println(platform.commandLineName() + " model audited       : " + s.snapshots);
            System.out.println(platform.commandLineName() + " DDL inspected       : " + s.ddlAvailable);
            System.out.println(platform.commandLineName() + " DDL unavailable     : " + s.ddlUnavailable);
            System.out.println(platform.commandLineName() + " physical violations : " + s.physicalViolations);
            System.out.println(platform.commandLineName() + " source value issues : " + s.sourceValueIssues);
            System.out.println(platform.commandLineName() + " source context review: " + s.sourceContextReviews);
            System.out.println(platform.commandLineName() + " FK recommendations  : " + s.expectedFkRecommendations);
        }
        System.out.println("Summary  : " + summaryTxt);
        System.out.println("Detail   : " + detailFile);
        System.out.println("Findings : " + findingsFile);

        assertTrue(!snapshots.isEmpty(), "No canonical JSON snapshots were found");
        if (failOnViolations) {
            assertTrue(totalViolations == 0,
                    "Physical Phase-1 corpus audit found " + totalViolations + " violation(s); see " + findingsFile);
        }
    }

    private void auditSnapshot(
            String snapshotName,
            String source,
            PreparedSchema prepared,
            DatabasePlatform platform,
            Stats stats,
            List<String> detail,
            List<String> findings) {

        stats.snapshots++;
        Dialect dialect = DialectFactory.create(platform);
        PhysicalCommentRenderer renderer = PhysicalCommentRendererResolver.resolve(dialect);
        DatabaseSchema schema = prepared.schema();

        Expected expected = new Expected();
        int violationsBefore = stats.physicalViolations;
        for (Table table : schema.tables()) {
            try {
                auditModelTable(snapshotName, source, platform, dialect, renderer, table, expected, stats, findings);
            } catch (Exception exception) {
                violation(findings, stats, snapshotName, source, platform, table.qualifiedName().toString(),
                        "PHYS-MODEL-AUDIT-001",
                        "Physical model audit could not evaluate this table",
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }

        boolean ddlAvailable = false;
        int actualTableBlocks = 0;
        int actualIndexBlocks = 0;
        int actualFkRecommendations = 0;
        int actualPaddedReviews = 0;
        String note = "";

        try {
            String sql = new DdlGenerator(dialect).generate(schema, prepared.validationReport());
            ddlAvailable = true;
            stats.ddlAvailable++;
            String tableTitle = tableBlockTitle(platform);
            String indexTitle = indexBlockTitle(platform);
            actualTableBlocks = countOccurrences(sql, tableTitle);
            actualIndexBlocks = countOccurrences(sql, indexTitle);
            actualFkRecommendations = countOccurrences(sql, "[PHYS-FK-INDEX-001]");
            actualPaddedReviews = countOccurrences(sql, "<PADDED_OR_NOT_PADDED>")
                    + countOccurrences(sql, "[SOURCE PHYSICAL] DB2_INDEX_PADDING=");

            verifyCount(snapshotName, source, platform, "PHYS-TABLE-BLOCK-001", "TABLE",
                    "Physical table comment block count differs from canonical table count",
                    expected.tableBlocks, actualTableBlocks, stats, findings);
            verifyCount(snapshotName, source, platform, "PHYS-INDEX-BLOCK-001", "INDEX",
                    "Physical index comment block count differs from expected PK/UK/index count",
                    expected.indexBlocks, actualIndexBlocks, stats, findings);
            verifyCount(snapshotName, source, platform, "PHYS-FK-INDEX-001", "FOREIGN_KEY",
                    "FK supporting-index recommendation count differs from canonical analysis",
                    expected.fkRecommendations, actualFkRecommendations, stats, findings);
            verifyCount(snapshotName, source, platform, "PHYS-DB2-PADDED-001", "INDEX",
                    "Db2 PADDED/NOT PADDED review marker count differs from varying-key analysis",
                    expected.paddedReviews, actualPaddedReviews, stats, findings);

            verifyPlaceholderCounts(snapshotName, source, platform, sql, expected.placeholderCounts, stats, findings);
            verifyActivePlacement(snapshotName, source, platform, sql, expected.activeClauses, stats, findings);
            verifyNoActivePhysicalRecommendations(snapshotName, source, platform, sql, stats, findings);
            verifyNoPhysicalBlockInsideFkOrCheck(snapshotName, source, platform, sql, stats, findings);
        } catch (Exception exception) {
            stats.ddlUnavailable++;
            note = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            finding(findings, snapshotName, source, platform, "INFO", "DDL",
                    "PHYS-DDL-UNAVAILABLE-001",
                    "Full DDL was unavailable; model/renderer physical audit still completed. This is not a physical violation.",
                    note);
        }

        stats.expectedTableBlocks += expected.tableBlocks;
        stats.expectedIndexBlocks += expected.indexBlocks;
        stats.expectedFkRecommendations += expected.fkRecommendations;
        stats.expectedPaddedReviews += expected.paddedReviews;
        if (ddlAvailable) {
            stats.actualTableBlocks += actualTableBlocks;
            stats.actualIndexBlocks += actualIndexBlocks;
            stats.actualFkRecommendations += actualFkRecommendations;
            stats.actualPaddedReviews += actualPaddedReviews;
        }

        int snapshotViolations = stats.physicalViolations - violationsBefore;
        String status = snapshotViolations == 0 ? (ddlAvailable ? "PASS" : "MODEL_PASS_DDL_UNAVAILABLE") : "VIOLATION";
        detail.add(csvLine(snapshotName, source, platform.commandLineName(), status,
                Integer.toString(schema.tables().size()), Boolean.toString(ddlAvailable),
                Integer.toString(expected.tableBlocks), Integer.toString(actualTableBlocks),
                Integer.toString(expected.indexBlocks), Integer.toString(actualIndexBlocks),
                Integer.toString(expected.fkRecommendations), Integer.toString(actualFkRecommendations),
                Integer.toString(expected.paddedReviews), Integer.toString(actualPaddedReviews),
                Integer.toString(snapshotViolations), note));
    }

    private void auditModelTable(
            String snapshot,
            String source,
            DatabasePlatform platform,
            Dialect dialect,
            PhysicalCommentRenderer renderer,
            Table table,
            Expected expected,
            Stats stats,
            List<String> findings) {

        expected.tableBlocks++;
        String tablePlacement = option(table, "TABLESPACE");
        if (blank(tablePlacement)) {
            tablePlacement = dialect.defaultTableTablespace(table.qualifiedName());
        }
        String activeTableClause = dialect.tableTablespaceClause(tablePlacement);
        boolean activeTablePlacement = !activeTableClause.isBlank();
        String tableBlock = renderer.tableOptions(table, activeTablePlacement);
        verifyCommentBlock(snapshot, source, platform, table.qualifiedName().toString(), tableBlock, stats, findings);
        collectPlaceholders(tableBlock, expected.placeholderCounts);
        if (activeTablePlacement) {
            increment(expected.activeClauses, normalizeSql(activeTableClause));
        }
        verifyTablePlacementPolicy(snapshot, source, platform, dialect, table, tableBlock,
                activeTablePlacement, stats, findings);

        List<List<Identifier>> physicalIndexKeys = physicalIndexKeys(table);
        int primaryKeyCount = table.primaryKey().isPresent() ? 1 : 0;
        int constraintIndexCount = primaryKeyCount + table.uniqueKeys().size();
        expected.indexBlocks += physicalIndexKeys.size();
        for (int i = 0; i < physicalIndexKeys.size(); i++) {
            List<Identifier> keyColumns = physicalIndexKeys.get(i);
            String indexPlacement = indexPlacementForPosition(table, dialect, i);
            String activeIndexClause = dialect.indexTablespaceClause(indexPlacement);
            boolean activeIndexPlacement = !activeIndexClause.isBlank();
            boolean constraintIndex = i < constraintIndexCount;
            String indexBlock = constraintIndex
                    ? renderer.constraintIndexOptions(table, keyColumns, activeIndexPlacement)
                    : renderer.indexOptions(table, keyColumns, activeIndexPlacement);
            verifyCommentBlock(snapshot, source, platform, table.qualifiedName() + "#index-" + (i + 1),
                    indexBlock, stats, findings);
            verifyIndexPlacementPolicy(snapshot, source, platform, table, indexBlock, activeIndexPlacement,
                    i < primaryKeyCount, stats, findings);
            collectPlaceholders(indexBlock, expected.placeholderCounts);
            if (activeIndexPlacement) {
                increment(expected.activeClauses, normalizeSql(activeIndexClause));
            }
            if (platform == DatabasePlatform.DB2_ZOS) {
                boolean expectedPadded = containsVaryingLengthCharacterKey(table, keyColumns);
                boolean renderedPadded = indexBlock.contains("<PADDED_OR_NOT_PADDED>")
                        || indexBlock.contains("[SOURCE PHYSICAL] DB2_INDEX_PADDING=");
                if (expectedPadded) {
                    expected.paddedReviews++;
                }
                if (expectedPadded != renderedPadded) {
                    violation(findings, stats, snapshot, source, platform, table.qualifiedName().toString(),
                            "PHYS-DB2-PADDED-MODEL-001",
                            "Db2 PADDED review marker does not match varying-length index-key analysis",
                            "expected=" + expectedPadded + "; rendered=" + renderedPadded + "; keys=" + keyColumns);
                }
            }
        }

        int missingFkIndexes = 0;
        for (ForeignKey foreignKey : table.foreignKeys()) {
            if (foreignKey.physicalReference() && !hasSupportingIndex(table, foreignKey.columns())) {
                missingFkIndexes++;
            }
        }
        expected.fkRecommendations += missingFkIndexes;
    }

    private void verifyIndexPlacementPolicy(
            String snapshot,
            String source,
            DatabasePlatform platform,
            Table table,
            String indexBlock,
            boolean activeIndexPlacement,
            boolean primaryKeyIndex,
            Stats stats,
            List<String> findings) {

        String location = table.qualifiedName() + (primaryKeyIndex ? "#primary-key-index" : "#index");
        String sourceIndexPlacement = option(table, "INDEX_TABLESPACE");
        if (blank(sourceIndexPlacement) && primaryKeyIndex) {
            sourceIndexPlacement = option(table, "PK_TABLESPACE");
        }
        boolean sourcePlacement = !blank(sourceIndexPlacement);

        if (platform != DatabasePlatform.DB2_ZOS && sourcePlacement && !activeIndexPlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-SOURCE-INDEX-PLACEMENT-001",
                    "Source index placement exists but the target dialect did not keep it active",
                    "placement=" + sourceIndexPlacement);
        }
        if (platform == DatabasePlatform.ORACLE && !activeIndexPlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-ORACLE-DEFAULT-INDEX-PLACEMENT-001",
                    "Oracle index placement must remain active via source placement or ITS_<SCHEMA> default", "");
        }
        if (platform != DatabasePlatform.ORACLE && platform != DatabasePlatform.DB2_ZOS
                && !sourcePlacement && activeIndexPlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-INVENTED-INDEX-PLACEMENT-001",
                    "Non-Oracle dialect invented an active index placement without source metadata", "");
        }

        if (platform == DatabasePlatform.DB2_ZOS) {
            verifyDb2SourceOrPlaceholder(findings, stats, snapshot, source, platform, location, table, indexBlock,
                    "<STOGROUP>", "DB2_INDEX_STOGROUP", "INDEX_STOGROUP");
            verifyDb2SourceOrPlaceholder(findings, stats, snapshot, source, platform, location, table, indexBlock,
                    "<PRIQTY>", "DB2_INDEX_PRIQTY", "INDEX_PRIQTY");
            verifyDb2SourceOrPlaceholder(findings, stats, snapshot, source, platform, location, table, indexBlock,
                    "<SECQTY>", "DB2_INDEX_SECQTY", "INDEX_SECQTY");
            verifyDb2SourceOrPlaceholder(findings, stats, snapshot, source, platform, location, table, indexBlock,
                    "<BUFFERPOOL>", "DB2_INDEX_BUFFERPOOL", "INDEX_BUFFERPOOL");
            return;
        }

        String placeholder = switch (platform) {
            case ORACLE, POSTGRESQL -> "<INDEX_TABLESPACE>";
            case SQLSERVER -> "<INDEX_FILEGROUP>";
            case DB2_ZOS -> "";
        };
        boolean placeholderPresent = !placeholder.isBlank() && indexBlock.contains(placeholder);
        if (activeIndexPlacement && placeholderPresent) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-INDEX-PLACEHOLDER-ACTIVE-001",
                    "Index placement placeholder is present even though placement is already active", placeholder);
        }
        if (!activeIndexPlacement && !placeholderPresent) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-INDEX-PLACEHOLDER-MISSING-001",
                    "Missing activation-ready index placement placeholder when no active placement exists", placeholder);
        }
    }

    private void verifyDb2SourceOrPlaceholder(
            List<String> findings, Stats stats, String snapshot, String source, DatabasePlatform platform,
            String location, Table table, String indexBlock, String placeholder, String... sourceKeys) {
        String sourceValue = null;
        for (String key : sourceKeys) {
            sourceValue = option(table, key);
            if (!blank(sourceValue)) {
                break;
            }
        }
        boolean represented = indexBlock.contains(placeholder)
                || (!blank(sourceValue) && indexBlock.contains(sourceValue));
        if (!represented) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-DB2-INDEX-SOURCE-OR-PLACEHOLDER-001",
                    "Db2 index physical block contains neither the source value nor a DBA/environment placeholder",
                    String.join("/", sourceKeys) + " source=" + safe(sourceValue) + "; placeholder=" + placeholder);
        }
    }

    private void verifyTablePlacementPolicy(
            String snapshot,
            String source,
            DatabasePlatform platform,
            Dialect dialect,
            Table table,
            String tableBlock,
            boolean activeTablePlacement,
            Stats stats,
            List<String> findings) {

        boolean sourcePlacement = !blank(option(table, "TABLESPACE"));
        String location = table.qualifiedName().toString();
        if (sourcePlacement && !activeTablePlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-SOURCE-TABLE-PLACEMENT-001",
                    "Source TABLESPACE exists but the target dialect did not keep an active table placement clause",
                    "TABLESPACE=" + option(table, "TABLESPACE"));
        }
        if (platform == DatabasePlatform.ORACLE && !activeTablePlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-ORACLE-DEFAULT-PLACEMENT-001",
                    "Oracle table placement must remain active via source TABLESPACE or TS_<SCHEMA> default", "");
        }
        if (platform != DatabasePlatform.ORACLE && !sourcePlacement && activeTablePlacement) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-INVENTED-TABLE-PLACEMENT-001",
                    "Non-Oracle dialect invented an active table placement without source metadata", "");
        }

        String placementPlaceholder = tablePlacementPlaceholder(platform);
        if (!placementPlaceholder.isBlank()) {
            boolean placeholderPresent = tableBlock.contains(placementPlaceholder);
            if (activeTablePlacement && placeholderPresent) {
                violation(findings, stats, snapshot, source, platform, location,
                        "PHYS-TABLE-PLACEHOLDER-ACTIVE-001",
                        "Table placement placeholder is present even though placement is already active",
                        placementPlaceholder);
            }
            if (!activeTablePlacement && !placeholderPresent) {
                violation(findings, stats, snapshot, source, platform, location,
                        "PHYS-TABLE-PLACEHOLDER-MISSING-001",
                        "Missing activation-ready table placement placeholder when no active placement exists",
                        placementPlaceholder);
            }
        }
    }

    private void verifyCommentBlock(
            String snapshot,
            String source,
            DatabasePlatform platform,
            String location,
            String block,
            Stats stats,
            List<String> findings) {
        String trimmed = block == null ? "" : block.trim();
        if (!trimmed.startsWith("/*") || !trimmed.endsWith("*/")) {
            violation(findings, stats, snapshot, source, platform, location,
                    "PHYS-COMMENT-BLOCK-001", "Physical recommendation is not enclosed in one block comment", trimmed);
        }
        for (String line : trimmed.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.startsWith("--") && candidate.contains(";")) {
                violation(findings, stats, snapshot, source, platform, location,
                        "PHYS-COMMENT-SEMICOLON-001",
                        "An activation-ready physical SQL line contains a statement terminator; the terminator must remain outside the block",
                        candidate);
            }
            if (line.contains("[SOURCE PHYSICAL ISSUE]")) {
                stats.sourceValueIssues++;
                finding(findings, snapshot, source, platform, "REVIEW", location,
                        "PHYS-SOURCE-VALUE-REVIEW-001",
                        "Source physical value was rejected or deemed inapplicable; the SQL block preserves the issue for DBA review",
                        line.trim());
            } else if (line.contains("[SOURCE PHYSICAL REVIEW]")) {
                stats.sourceContextReviews++;
                finding(findings, snapshot, source, platform, "INFO", location,
                        "PHYS-SOURCE-CONTEXT-REVIEW-001",
                        "Source physical value is syntactically usable but depends on storage, version, uniqueness, or workload context that was not inferred",
                        line.trim());
            }
        }
    }

    private void verifyPlaceholderCounts(
            String snapshot,
            String source,
            DatabasePlatform platform,
            String sql,
            Map<String, Integer> expected,
            Stats stats,
            List<String> findings) {
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            int actual = countOccurrences(sql, entry.getKey());
            if (actual != entry.getValue()) {
                violation(findings, stats, snapshot, source, platform, "DDL",
                        "PHYS-PLACEHOLDER-COUNT-001",
                        "Physical placeholder count differs from renderer/model expectation",
                        entry.getKey() + ": expected=" + entry.getValue() + ", actual=" + actual);
            }
        }
    }

    private void verifyActivePlacement(
            String snapshot,
            String source,
            DatabasePlatform platform,
            String sql,
            Map<String, Integer> expectedClauses,
            Stats stats,
            List<String> findings) {
        String active = normalizeSql(stripComments(sql));
        for (Map.Entry<String, Integer> entry : expectedClauses.entrySet()) {
            int actual = countOccurrences(active, entry.getKey());
            if (actual < entry.getValue()) {
                violation(findings, stats, snapshot, source, platform, "DDL",
                        "PHYS-ACTIVE-PLACEMENT-001",
                        "Expected active source/default placement is missing from executable SQL",
                        entry.getKey() + ": expected-at-least=" + entry.getValue() + ", actual=" + actual);
            }
        }
    }

    private void verifyNoActivePhysicalRecommendations(
            String snapshot,
            String source,
            DatabasePlatform platform,
            String sql,
            Stats stats,
            List<String> findings) {
        String active = normalizeSql(stripComments(sql));
        List<String> forbidden = switch (platform) {
            case ORACLE -> List.of("PCTFREE 10", "INITRANS 1", "INITRANS 2");
            case POSTGRESQL -> List.of("WITH (FILLFACTOR = 100)", "WITH (FILLFACTOR = 90)");
            case SQLSERVER -> List.of("DATA_COMPRESSION = NONE", "FILLFACTOR = 0", "PAD_INDEX = OFF");
            case DB2_ZOS -> List.of("FREEPAGE 0", "PCTFREE 10", "GBPCACHE CHANGED", "COMPRESS NO");
        };
        for (String token : forbidden) {
            if (active.contains(token)) {
                violation(findings, stats, snapshot, source, platform, "DDL",
                        "PHYS-RECOMMENDATION-ACTIVE-001",
                        "A Phase-1 recommendation became executable SQL instead of remaining commented", token);
            }
        }
        for (String token : physicalPlaceholderTokens()) {
            if (active.contains(token)) {
                violation(findings, stats, snapshot, source, platform, "DDL",
                        "PHYS-PLACEHOLDER-ACTIVE-001",
                        "An environment placeholder escaped its comment block into executable SQL", token);
            }
        }
    }

    private void verifyNoPhysicalBlockInsideFkOrCheck(
            String snapshot,
            String source,
            DatabasePlatform platform,
            String sql,
            Stats stats,
            List<String> findings) {
        for (String segment : statementParser.parse(sql, platform)) {
            String upper = segment.toUpperCase(Locale.ROOT);
            boolean physicalBlock = upper.contains("PHYSICAL OPTIONS");
            if (!physicalBlock) {
                continue;
            }
            if (upper.contains(" FOREIGN KEY ")) {
                violation(findings, stats, snapshot, source, platform, "FOREIGN_KEY",
                        "PHYS-FK-STORAGE-001", "A FOREIGN KEY statement contains a physical option block", snippet(segment));
            }
            if (CHECK_CONSTRAINT.matcher(segment).find()) {
                violation(findings, stats, snapshot, source, platform, "CHECK",
                        "PHYS-CHECK-STORAGE-001", "A CHECK constraint statement contains a physical option block", snippet(segment));
            }
        }
    }

    private List<List<Identifier>> physicalIndexKeys(Table table) {
        List<List<Identifier>> result = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        table.primaryKey().ifPresent(primaryKey -> {
            result.add(primaryKey.columns());
            signatures.add(identifierSignature(primaryKey.columns()));
        });
        table.uniqueKeys().forEach(unique -> {
            result.add(unique.columns());
            signatures.add(identifierSignature(unique.columns()));
        });
        for (Index index : table.indexes()) {
            List<IndexColumn> normalized = deduplicateIndexColumns(index.columns());
            String signature = indexSignature(normalized);
            if (!signatures.add(signature)) {
                continue;
            }
            result.add(normalized.stream()
                    .filter(column -> !column.expressionBased())
                    .map(IndexColumn::column)
                    .toList());
        }
        return List.copyOf(result);
    }

    private String indexPlacementForPosition(Table table, Dialect dialect, int position) {
        int pkCount = table.primaryKey().isPresent() ? 1 : 0;
        String indexTablespace = option(table, "INDEX_TABLESPACE");
        if (position < pkCount) {
            if (blank(indexTablespace)) {
                indexTablespace = option(table, "PK_TABLESPACE");
            }
        }
        if (blank(indexTablespace)) {
            indexTablespace = dialect.defaultIndexTablespace(table.qualifiedName());
        }
        return indexTablespace;
    }

    private boolean hasSupportingIndex(Table table, List<Identifier> foreignKeyColumns) {
        if (table.primaryKey().isPresent()
                && leadingColumnsMatch(table.primaryKey().get().columns(), foreignKeyColumns)) {
            return true;
        }
        if (table.uniqueKeys().stream().anyMatch(unique -> leadingColumnsMatch(unique.columns(), foreignKeyColumns))) {
            return true;
        }
        for (Index index : table.indexes()) {
            List<Identifier> indexColumns = new ArrayList<>();
            boolean expressionBeforeMatchBoundary = false;
            for (IndexColumn indexColumn : index.columns()) {
                if (indexColumn.expressionBased()) {
                    expressionBeforeMatchBoundary = true;
                    break;
                }
                indexColumns.add(indexColumn.column());
                if (indexColumns.size() >= foreignKeyColumns.size()) {
                    break;
                }
            }
            if (!expressionBeforeMatchBoundary && leadingColumnsMatch(indexColumns, foreignKeyColumns)) {
                return true;
            }
        }
        return false;
    }

    private boolean leadingColumnsMatch(List<Identifier> candidate, List<Identifier> required) {
        if (candidate.size() < required.size()) {
            return false;
        }
        for (int i = 0; i < required.size(); i++) {
            if (!candidate.get(i).normalized().equals(required.get(i).normalized())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsVaryingLengthCharacterKey(Table table, List<Identifier> keyColumns) {
        for (Identifier key : keyColumns) {
            Column column = table.findColumn(key.value()).orElse(null);
            if (column == null) {
                continue;
            }
            String type = column.dataType().name().normalized().toUpperCase(Locale.ROOT);
            if (type.equals("VARCHAR") || type.equals("VARCHAR2") || type.equals("NVARCHAR")
                    || type.equals("NVARCHAR2") || type.equals("VARGRAPHIC")) {
                return true;
            }
        }
        return false;
    }

    private List<IndexColumn> deduplicateIndexColumns(List<IndexColumn> columns) {
        Set<String> seen = new LinkedHashSet<>();
        List<IndexColumn> result = new ArrayList<>();
        for (IndexColumn column : columns) {
            String signature = indexColumnSignature(column);
            if (seen.add(signature)) {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    private String identifierSignature(List<Identifier> columns) {
        return columns.stream().map(identifier -> identifier.normalized().toUpperCase(Locale.ROOT) + ":ASC")
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    private String indexSignature(List<IndexColumn> columns) {
        return columns.stream().map(this::indexColumnSignature).reduce((a, b) -> a + "|" + b).orElse("");
    }

    private String indexColumnSignature(IndexColumn column) {
        if (column.expressionBased()) {
            return "EXPR:" + column.expression().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT)
                    + ":" + column.direction();
        }
        SortDirection direction = column.direction();
        return column.column().normalized().toUpperCase(Locale.ROOT) + ":" + direction;
    }

    private void collectPlaceholders(String block, Map<String, Integer> counts) {
        for (String token : physicalPlaceholderTokens()) {
            int count = countOccurrences(block, token);
            if (count > 0) {
                counts.merge(token, count, Integer::sum);
            }
        }
    }

    private List<String> physicalPlaceholderTokens() {
        return List.of("<TABLE_TABLESPACE>", "<INDEX_TABLESPACE>", "<TABLE_FILEGROUP>", "<INDEX_FILEGROUP>",
                "<DATABASE>", "<TABLESPACE>", "<STOGROUP>", "<PRIQTY>", "<SECQTY>", "<BUFFERPOOL>",
                "<PADDED_OR_NOT_PADDED>", "<PCTFREE>", "<PCTUSED>", "<INITRANS>",
                "<INDEX_PCTFREE>", "<INDEX_INITRANS>", "<TABLE_COMPRESSION>",
                "<INDEX_COMPRESSION>", "<TABLE_FILLFACTOR>", "<INDEX_FILLFACTOR>",
                "<INDEX_DEDUPLICATE_ITEMS>", "<TABLE_DATA_COMPRESSION>", "<PAD_INDEX>",
                "<IGNORE_DUP_KEY>", "<STATISTICS_NORECOMPUTE>", "<ALLOW_ROW_LOCKS>", "<ALLOW_PAGE_LOCKS>",
                "<INDEX_DATA_COMPRESSION>", "<OPTIMIZE_FOR_SEQUENTIAL_KEY>",
                "<TABLE_XML_COMPRESSION>", "<INDEX_XML_COMPRESSION>", "<STATISTICS_INCREMENTAL>",
                "<TOAST_TUPLE_TARGET>",
                "<PARALLEL_WORKERS>", "<GIST_BUFFERING>", "<GIN_FASTUPDATE>", "<GIN_PENDING_LIST_LIMIT>",
                "<BRIN_PAGES_PER_RANGE>", "<BRIN_AUTOSUMMARIZE>", "<ERASE>", "<FREEPAGE>", "<GBPCACHE>", "<COMPRESS>", "<CLOSE>", "<PIECESIZE>",
                "<LOGGING_OR_NOLOGGING>", "<PARALLEL_CLAUSE>", "<DEFERRED_OR_IMMEDIATE>");
    }

    private String tablePlacementPlaceholder(DatabasePlatform platform) {
        return switch (platform) {
            case ORACLE, POSTGRESQL -> "<TABLE_TABLESPACE>";
            case SQLSERVER -> "<TABLE_FILEGROUP>";
            case DB2_ZOS -> "<DATABASE>.<TABLESPACE>";
        };
    }

    private String tableBlockTitle(DatabasePlatform platform) {
        return switch (platform) {
            case ORACLE -> "-- ORACLE TABLE PHYSICAL OPTIONS";
            case POSTGRESQL -> "-- POSTGRESQL TABLE PHYSICAL OPTIONS";
            case SQLSERVER -> "-- SQL SERVER TABLE PHYSICAL OPTIONS";
            case DB2_ZOS -> "-- DB2/ZOS TABLE PHYSICAL OPTIONS";
        };
    }

    private String indexBlockTitle(DatabasePlatform platform) {
        return switch (platform) {
            case ORACLE -> "-- ORACLE INDEX PHYSICAL OPTIONS";
            case POSTGRESQL -> "-- POSTGRESQL INDEX PHYSICAL OPTIONS";
            case SQLSERVER -> "-- SQL SERVER INDEX PHYSICAL OPTIONS";
            case DB2_ZOS -> "-- DB2/ZOS INDEX PHYSICAL OPTIONS";
        };
    }

    private String option(Table table, String key) {
        return table.physicalOptions().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .findFirst().orElse(null);
    }

    private void verifyCount(
            String snapshot, String source, DatabasePlatform platform, String code, String location,
            String message, int expected, int actual, Stats stats, List<String> findings) {
        if (expected != actual) {
            violation(findings, stats, snapshot, source, platform, location, code, message,
                    "expected=" + expected + "; actual=" + actual);
        }
    }

    private void violation(
            List<String> findings, Stats stats, String snapshot, String source, DatabasePlatform platform,
            String location, String code, String message, String evidence) {
        stats.physicalViolations++;
        finding(findings, snapshot, source, platform, "ERROR", location, code, message, evidence);
    }

    private void finding(
            List<String> findings, String snapshot, String source, DatabasePlatform platform,
            String severity, String location, String code, String message, String evidence) {
        findings.add(csvLine(snapshot, source, platform.commandLineName(), severity, location, code, message, evidence));
    }

    private String summaryCsv(List<DatabasePlatform> platforms, Map<DatabasePlatform, Stats> stats) {
        List<String> lines = new ArrayList<>();
        lines.add("platform,snapshots,ddl_inspected,ddl_unavailable,physical_violations,source_value_issues,"
                + "source_context_reviews,expected_table_blocks,actual_table_blocks,expected_index_blocks,"
                + "actual_index_blocks,expected_fk_recommendations,actual_fk_recommendations,"
                + "expected_padded_reviews,actual_padded_reviews");
        for (DatabasePlatform platform : platforms) {
            Stats s = stats.get(platform);
            lines.add(csvLine(platform.commandLineName(), Integer.toString(s.snapshots),
                    Integer.toString(s.ddlAvailable), Integer.toString(s.ddlUnavailable),
                    Integer.toString(s.physicalViolations), Integer.toString(s.sourceValueIssues),
                    Integer.toString(s.sourceContextReviews), Integer.toString(s.expectedTableBlocks),
                    Integer.toString(s.actualTableBlocks), Integer.toString(s.expectedIndexBlocks),
                    Integer.toString(s.actualIndexBlocks), Integer.toString(s.expectedFkRecommendations),
                    Integer.toString(s.actualFkRecommendations), Integer.toString(s.expectedPaddedReviews),
                    Integer.toString(s.actualPaddedReviews)));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private String summaryText(
            Path inputRoot, int snapshotCount, int snapshotFailures,
            List<DatabasePlatform> platforms, Map<DatabasePlatform, Stats> stats) {
        StringBuilder out = new StringBuilder();
        out.append("SchemaForge Physical Phase-1 corpus audit").append(System.lineSeparator())
                .append("=========================================").append(System.lineSeparator())
                .append("Canonical JSON root : ").append(inputRoot).append(System.lineSeparator())
                .append("Snapshots discovered: ").append(snapshotCount).append(System.lineSeparator())
                .append("Snapshot failures   : ").append(snapshotFailures).append(System.lineSeparator())
                .append("Scope               : physical-only; datatype compatibility is not a failure gate")
                .append(System.lineSeparator()).append(System.lineSeparator());
        for (DatabasePlatform platform : platforms) {
            Stats s = stats.get(platform);
            out.append(platform.commandLineName()).append(System.lineSeparator())
                    .append("  Model audited       : ").append(s.snapshots).append(System.lineSeparator())
                    .append("  DDL inspected       : ").append(s.ddlAvailable).append(System.lineSeparator())
                    .append("  DDL unavailable     : ").append(s.ddlUnavailable).append(System.lineSeparator())
                    .append("  Physical violations : ").append(s.physicalViolations).append(System.lineSeparator())
                    .append("  Source value issues : ").append(s.sourceValueIssues).append(System.lineSeparator())
                    .append("  Source context review: ").append(s.sourceContextReviews).append(System.lineSeparator())
                    .append("  Table blocks        : ").append(s.actualTableBlocks).append(" / ")
                    .append(s.expectedTableBlocks).append(" inspected/expected").append(System.lineSeparator())
                    .append("  Index blocks        : ").append(s.actualIndexBlocks).append(" / ")
                    .append(s.expectedIndexBlocks).append(" inspected/expected").append(System.lineSeparator())
                    .append("  FK recommendations  : ").append(s.expectedFkRecommendations).append(System.lineSeparator())
                    .append("  Db2 PADDED reviews  : ").append(s.expectedPaddedReviews).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    private List<DatabasePlatform> configuredPlatforms() {
        String raw = System.getProperty(PLATFORMS, "");
        if (raw.isBlank()) {
            return DEFAULT_PLATFORMS;
        }
        List<DatabasePlatform> result = new ArrayList<>();
        for (String value : raw.split(",")) {
            if (!value.isBlank()) {
                DatabasePlatform platform = DatabasePlatform.parse(value);
                if (!result.contains(platform)) {
                    result.add(platform);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No physical audit platform selected");
        }
        return List.copyOf(result);
    }

    private Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required system property is missing: -D" + property + "=<directory>");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory does not exist for " + property + ": " + path);
        }
        return path;
    }

    private Path outputDirectory(Path inputRoot) {
        String value = System.getProperty(OUTPUT_DIR);
        if (value == null || value.isBlank()) {
            return inputRoot.resolveSibling("schemaforge-physical-audit").toAbsolutePath().normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private String stripComments(String sql) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
    }

    private String normalizeSql(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private int countOccurrences(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private void increment(Map<String, Integer> map, String key) {
        if (!key.isBlank()) {
            map.merge(key, 1, Integer::sum);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String snippet(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }

    private String safeMessage(Exception exception) {
        return safe(exception.getMessage()).replaceAll("\\s+", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String csvLine(String... values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            String safe = value == null ? "" : value;
            if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
                escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
            } else {
                escaped.add(safe);
            }
        }
        return String.join(",", escaped);
    }

    private static final class Expected {
        int tableBlocks;
        int indexBlocks;
        int fkRecommendations;
        int paddedReviews;
        final Map<String, Integer> placeholderCounts = new LinkedHashMap<>();
        final Map<String, Integer> activeClauses = new LinkedHashMap<>();
    }

    private static final class Stats {
        int snapshots;
        int ddlAvailable;
        int ddlUnavailable;
        int physicalViolations;
        int sourceValueIssues;
        int sourceContextReviews;
        int expectedTableBlocks;
        int actualTableBlocks;
        int expectedIndexBlocks;
        int actualIndexBlocks;
        int expectedFkRecommendations;
        int actualFkRecommendations;
        int expectedPaddedReviews;
        int actualPaddedReviews;
    }
}
