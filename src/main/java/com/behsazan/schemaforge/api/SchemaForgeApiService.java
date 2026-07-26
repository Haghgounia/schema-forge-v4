package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.reporting.SchemaCompareExcelWriter;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.ea.EnterpriseArchitectXmlParser;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates schema forge api operations.
 *
 * @since 4.1
 */
@Service
public class SchemaForgeApiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaForgeApiService.class);
    private final SchemaPreparationService preparationService;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final EaImportProperties eaImportProperties;
    private final ObjectMapper objectMapper;
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final SchemaCompareExcelWriter compareExcelWriter = new SchemaCompareExcelWriter();

    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver) {
        this(auditProperties, grantProperties, spellCheckProperties, objectMapper,
                metadataRepositoryResolver, EaImportProperties.defaults());
    }

    @Autowired
    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties) {
        this.preparationService = new SchemaPreparationService(
                auditProperties, grantProperties, spellCheckProperties, objectMapper);
        this.metadataRepositoryResolver = metadataRepositoryResolver;
        this.eaImportProperties = eaImportProperties;
        this.objectMapper = objectMapper;
    }

    public byte[] generateFromWord(MultipartFile file) throws IOException {
        requireExtension(file, ".docx");
        Path work = Files.createTempDirectory("schemaforge-word-");
        try {
            Path input = work.resolve(safeName(file.getOriginalFilename(), "input.docx"));
            file.transferTo(input);
            Path output = Files.createDirectories(work.resolve("output"));
            generateWordForBoth(input, output);
            return zipDirectory(output);
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromZip(MultipartFile file) throws IOException {
        requireExtension(file, ".zip");
        Path work = Files.createTempDirectory("schemaforge-zip-");
        try {
            Path inputDir = Files.createDirectories(work.resolve("input"));
            Path outputDir = Files.createDirectories(work.resolve("output"));
            unzipSafely(file, inputDir);
            try (var files = Files.walk(inputDir)) {
                var documents = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
                        .toList();
                if (documents.isEmpty()) throw new IllegalArgumentException("ZIP does not contain any DOCX files");
                for (Path document : documents) generateWordForBoth(document, outputDir);
            }
            return zipDirectory(outputDir);
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromEaXml(MultipartFile file) throws IOException {
        String name = safeName(file.getOriginalFilename(), "ea-model.xml");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml") && !lower.endsWith(".xmi")) throw new IllegalArgumentException("EA file must be XML or XMI");
        Path work = Files.createTempDirectory("schemaforge-ea-");
        try {
            DatabaseSchema parsed;
            try (InputStream inputStream = file.getInputStream()) {
                parsed = new EnterpriseArchitectXmlParser(
                        eaImportProperties.getDefaultSchema()).parse(name, inputStream);
            }
            PreparedSchema prepared = preparationService.prepare(parsed);
            Path output = Files.createDirectories(work.resolve("output"));
            writeEaPerTableOutputs(prepared, output, stripExtension(name));
            return zipDirectory(output);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Parses and enriches the Word model only once. Oracle and PostgreSQL are generated
     * from the exact same enriched model, so configured audit columns cannot diverge.
     */
    private void generateWordForBoth(Path input, Path output) throws IOException {
        DatabaseSchema parsed;
        try (InputStream stream = Files.newInputStream(input)) {
            parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(input.getFileName().toString(), stream));
        }
        PreparedSchema prepared = preparationService.prepare(parsed);
        writeAllDatabaseOutputs(prepared, output, stripExtension(input.getFileName().toString()));
    }

    private void writeAllDatabaseOutputs(PreparedSchema prepared, Path output, String baseName) throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());

        // All artifacts for one source document share the same timestamp.
        String timestamp = outputFileNamer.create(output, baseName, DatabasePlatform.ORACLE).timestamp();
        String timestampedBaseName = baseName + "_" + timestamp;

        // Metadata is queried once per database output. The same comparison result is
        // reused by SQL generation and the consolidated JSON validation report.
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            var dialect = DialectFactory.create(platform);
            MetadataRepository repository = metadataRepositoryResolver.resolve(platform);
            MetadataComparisonResult metadata = new MetadataComparisonValidator(
                    dialect, repository).validate(schema);
            metadata.issues().stream()
                    .map(issue -> new ValidationIssue(
                            issue.severity(),
                            issue.code(),
                            "dialects." + platform.commandLineName() + "." + issue.path(),
                            "[" + platform.name() + "] " + issue.message()))
                    .forEach(jsonIssues::add);

            String sql = new DdlGenerator(dialect).generate(schema, report, metadata);
            Files.writeString(
                    output.resolve(timestampedBaseName + "." + platform.commandLineName() + ".sql"),
                    sql,
                    StandardCharsets.UTF_8);

            writeComparisonWorkbooks(schema, repository, metadata, output, timestamp, platform, dialect);
        }

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        new JsonExporter().write(output.resolve(timestampedBaseName + ".json"), schema, jsonReport);
    }


    /**
     * EA exports can contain many tables. Each table is therefore emitted as an
     * independent Oracle/PostgreSQL script and, when metadata is available, an
     * independent comparison workbook. The canonical model and a manifest remain
     * consolidated at archive root.
     */
    private void writeEaPerTableOutputs(PreparedSchema prepared, Path output, String baseName) throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());
        String timestamp = outputFileNamer.create(output, baseName, DatabasePlatform.ORACLE).timestamp();

        Map<String, Map<String, Object>> manifestTables = new LinkedHashMap<>();
        for (Table table : schema.tables()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("schema", tableSchema(schema, table));
            item.put("table", table.qualifiedName().name().value());
            manifestTables.put(tableKey(table), item);
        }

        DependencyOrder dependencyOrder = dependencyOrder(schema.tables());

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Dialect dialect = DialectFactory.create(platform);
            MetadataRepository repository = metadataRepositoryResolver.resolve(platform);
            MetadataComparisonResult metadata = new MetadataComparisonValidator(dialect, repository).validate(schema);
            metadata.issues().stream()
                    .map(issue -> new ValidationIssue(
                            issue.severity(),
                            issue.code(),
                            "dialects." + platform.commandLineName() + "." + issue.path(),
                            "[" + platform.name() + "] " + issue.message()))
                    .forEach(jsonIssues::add);

            Path sqlDirectory = Files.createDirectories(output.resolve(platform.commandLineName()));
            Path comparisonDirectory = Files.createDirectories(
                    output.resolve("comparison").resolve(platform.commandLineName()));

            for (Table table : schema.tables()) {
                DatabaseSchema tableSchema = singleTableSchema(schema, table);
                ValidationReport tableReport = validationForTable(report, table);
                MetadataComparisonResult tableMetadata = metadataForTable(metadata, table);
                String sql = new DdlGenerator(dialect).generate(tableSchema, tableReport, tableMetadata);
                String sqlFileName = eaSqlFileName(schema, table, platform);
                Files.writeString(sqlDirectory.resolve(sqlFileName), sql, StandardCharsets.UTF_8);

                Map<String, Object> item = manifestTables.get(tableKey(table));
                item.put(platform.commandLineName() + "Sql",
                        platform.commandLineName() + "/" + sqlFileName);

                String workbook = writeEaComparisonWorkbook(
                        schema, table, repository, metadata, comparisonDirectory, platform, dialect);
                if (workbook != null) {
                    item.put(platform.commandLineName() + "Excel",
                            "comparison/" + platform.commandLineName() + "/" + workbook);
                }
            }

            writeEaRunAll(schema, sqlDirectory, platform, dependencyOrder, timestamp);
        }

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        new JsonExporter().write(output.resolve("model.json"), schema, jsonReport);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("sourceFile", sourceFileName(schema, baseName));
        manifest.put("generatedAt", timestamp);
        manifest.put("schema", schema.name().value());
        manifest.put("tableCount", schema.tables().size());
        manifest.put("dependencyOrder", dependencyOrder.tables().stream()
                .map(table -> table.qualifiedName().toString()).toList());
        manifest.put("cyclicTables", dependencyOrder.cyclicTables().stream()
                .map(table -> table.qualifiedName().toString()).toList());
        manifest.put("tables", new ArrayList<>(manifestTables.values()));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("manifest.json").toFile(), manifest);
    }

    private String writeEaComparisonWorkbook(
            DatabaseSchema schema,
            Table documentTable,
            MetadataRepository repository,
            MetadataComparisonResult metadata,
            Path output,
            DatabasePlatform platform,
            Dialect dialect) throws IOException {

        if (!repository.available()) return null;

        String schemaName = tableSchema(schema, documentTable);
        String tableName = documentTable.qualifiedName().name().value();
        var databaseTable = repository.findTable(schemaName, tableName);
        if (databaseTable.isEmpty()) {
            List<String> candidateSchemas = repository.findTableSchemas(tableName);
            String matchedSchema = candidateSchemas.stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                    .findFirst()
                    .orElse(null);
            if (matchedSchema != null) {
                databaseTable = repository.findTable(matchedSchema, tableName);
            }
        }
        if (databaseTable.isEmpty()) {
            LOGGER.warn("[{}] EA comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                    platform.name(), schemaName, tableName);
            return null;
        }

        Map<String, Long> usageCounts = new LinkedHashMap<>();
        documentTable.columns().forEach(column -> usageCounts.put(
                column.name().normalized(),
                metadata.frequency(MetadataComparisonValidator.path(documentTable, column))));

        byte[] workbook = compareExcelWriter.write(
                documentTable, databaseTable.get(), usageCounts, platform.name(), dialect);
        String fileName = eaArtifactBaseName(schema, documentTable, platform)
                + "." + platform.commandLineName() + ".xlsx";
        Files.write(output.resolve(fileName), workbook);
        LOGGER.info("[{}] EA comparison workbook generated: {}", platform.name(), fileName);
        return fileName;
    }

    private void writeEaRunAll(
            DatabaseSchema schema,
            Path sqlDirectory,
            DatabasePlatform platform,
            DependencyOrder order,
            String timestamp) throws IOException {

        StringBuilder script = new StringBuilder();
        String comment = "--";
        script.append(comment).append(" SchemaForge EA run-all script").append(System.lineSeparator())
                .append(comment).append(" Schema: ").append(schema.name().value()).append(System.lineSeparator())
                .append(comment).append(" Generated: ").append(timestamp).append(System.lineSeparator());
        if (!order.cyclicTables().isEmpty()) {
            script.append(comment)
                    .append(" WARNING: cyclic internal foreign-key dependencies detected for: ")
                    .append(order.cyclicTables().stream()
                            .map(table -> table.qualifiedName().toString())
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append(System.lineSeparator());
        }
        script.append(System.lineSeparator());

        for (Table table : order.tables()) {
            String fileName = eaSqlFileName(schema, table, platform);
            if (platform == DatabasePlatform.ORACLE) {
                script.append("@@").append(fileName);
            } else {
                script.append("\\ir ").append(fileName);
            }
            script.append(System.lineSeparator());
        }
        Files.writeString(sqlDirectory.resolve("run_all.sql"), script.toString(), StandardCharsets.UTF_8);
    }

    private DatabaseSchema singleTableSchema(DatabaseSchema source, Table table) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder(source.name().value())
                .description(source.description().value());
        source.metadata().forEach(builder::metadata);
        relevantSequences(source, table).forEach(builder::addSequence);
        builder.addTable(table);
        return builder.build();
    }

    private List<Sequence> relevantSequences(DatabaseSchema schema, Table table) {
        if (schema.sequences().isEmpty()) return List.of();
        List<Sequence> result = new ArrayList<>();
        for (Sequence sequence : schema.sequences()) {
            String simpleName = sequence.qualifiedName().name().value().toUpperCase(Locale.ROOT);
            String qualifiedName = sequence.qualifiedName().toString().toUpperCase(Locale.ROOT);
            boolean used = table.columns().stream()
                    .map(column -> column.defaultValue())
                    .filter(DefaultValue::isPresent)
                    .map(defaultValue -> defaultValue.expression().toUpperCase(Locale.ROOT))
                    .anyMatch(expression -> expression.contains(qualifiedName) || expression.contains(simpleName));
            if (used) result.add(sequence);
        }
        return result;
    }

    private ValidationReport validationForTable(ValidationReport report, Table table) {
        List<ValidationIssue> issues = report.issues().stream()
                .filter(issue -> issueAppliesToTable(issue, table))
                .toList();
        return new ValidationReport(
                issues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                issues);
    }

    private MetadataComparisonResult metadataForTable(MetadataComparisonResult metadata, Table table) {
        String prefix = MetadataComparisonValidator.tablePath(table);
        Map<String, Long> frequencies = metadata.columnFrequencies().entrySet().stream()
                .filter(entry -> entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + "."))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, String> resolvedSchemas = metadata.resolvedForeignKeySchemas().entrySet().stream()
                .filter(entry -> entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + "."))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ValidationIssue> issues = metadata.issues().stream()
                .filter(issue -> issueAppliesToTable(issue, table))
                .toList();
        return new MetadataComparisonResult(issues, frequencies, resolvedSchemas, metadata.metadataAvailable());
    }

    private boolean issueAppliesToTable(ValidationIssue issue, Table table) {
        String path = issue.path();
        if (path == null || path.isBlank() || !path.startsWith("tables.")) return true;
        String prefix = MetadataComparisonValidator.tablePath(table);
        return path.equals(prefix) || path.startsWith(prefix + ".");
    }

    private DependencyOrder dependencyOrder(List<Table> tables) {
        Map<String, Table> byName = new LinkedHashMap<>();
        for (Table table : tables) byName.put(tableKey(table), table);

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        byName.keySet().forEach(key -> {
            indegree.put(key, 0);
            dependents.put(key, new LinkedHashSet<>());
        });

        for (Table source : tables) {
            String sourceKey = tableKey(source);
            Set<String> dependencies = new LinkedHashSet<>();
            for (ForeignKey foreignKey : source.foreignKeys()) {
                String targetKey = resolveInternalTableKey(source, foreignKey, byName);
                if (targetKey != null && !targetKey.equals(sourceKey)) dependencies.add(targetKey);
            }
            indegree.put(sourceKey, dependencies.size());
            dependencies.forEach(target -> dependents.get(target).add(sourceKey));
        }

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((key, value) -> { if (value == 0) queue.addLast(key); });
        List<Table> ordered = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            if (!emitted.add(key)) continue;
            ordered.add(byName.get(key));
            for (String dependent : dependents.getOrDefault(key, Set.of())) {
                int next = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                if (next == 0) queue.addLast(dependent);
            }
        }

        List<Table> cyclic = new ArrayList<>();
        for (Table table : tables) {
            if (!emitted.contains(tableKey(table))) {
                cyclic.add(table);
                ordered.add(table);
            }
        }
        return new DependencyOrder(List.copyOf(ordered), List.copyOf(cyclic));
    }

    private String resolveInternalTableKey(
            Table source,
            ForeignKey foreignKey,
            Map<String, Table> byName) {
        String targetName = foreignKey.referencedTable().name().normalized();
        String targetSchema = foreignKey.referencedTable().schemaName()
                .map(identifier -> identifier.normalized())
                .orElseGet(() -> source.qualifiedName().schemaName()
                        .map(identifier -> identifier.normalized()).orElse(""));
        String exact = targetSchema + "." + targetName;
        if (byName.containsKey(exact)) return exact;
        return byName.entrySet().stream()
                .filter(entry -> entry.getValue().qualifiedName().name().normalized().equals(targetName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private String eaSqlFileName(DatabaseSchema schema, Table table, DatabasePlatform platform) {
        return eaArtifactBaseName(schema, table, platform)
                + "." + platform.commandLineName() + ".sql";
    }

    private String eaArtifactBaseName(DatabaseSchema schema, Table table, DatabasePlatform platform) {
        String value = tableSchema(schema, table) + "." + table.qualifiedName().name().value();
        return platform == DatabasePlatform.POSTGRESQL
                ? value.toLowerCase(Locale.ROOT)
                : value;
    }

    private String tableSchema(DatabaseSchema schema, Table table) {
        return table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value());
    }

    private String tableKey(Table table) {
        String schema = table.qualifiedName().schemaName()
                .map(identifier -> identifier.normalized()).orElse("");
        return schema + "." + table.qualifiedName().name().normalized();
    }

    private String sourceFileName(DatabaseSchema schema, String fallback) {
        return schema.metadata().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("source.fileName"))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private record DependencyOrder(List<Table> tables, List<Table> cyclicTables) { }

    private void writeComparisonWorkbooks(
            DatabaseSchema schema,
            MetadataRepository repository,
            MetadataComparisonResult metadata,
            Path output,
            String timestamp,
            DatabasePlatform platform,
            Dialect dialect) throws IOException {

        if (!repository.available()) return;

        for (Table documentTable : schema.tables()) {
            String schemaName = documentTable.qualifiedName().schemaName()
                    .map(identifier -> identifier.value())
                    .orElse(schema.name().value());
            String tableName = documentTable.qualifiedName().name().value();
            var databaseTable = repository.findTable(schemaName, tableName);
            if (databaseTable.isEmpty()) {
                List<String> candidateSchemas = repository.findTableSchemas(tableName);
                String matchedSchema = candidateSchemas.stream()
                        .filter(candidate -> candidate.equalsIgnoreCase(schemaName))
                        .findFirst()
                        .orElse(null);
                if (matchedSchema != null) {
                    databaseTable = repository.findTable(matchedSchema, tableName);
                }
            }
            if (databaseTable.isEmpty()) {
                LOGGER.warn("[{}] Comparison workbook skipped; table not found. requestedSchema={}, requestedTable={}",
                        platform.name(), schemaName, tableName);
                continue;
            }
            LOGGER.info("[{}] Comparison table resolved. requested={}.{}, actual={}",
                    platform.name(), schemaName, tableName,
                    databaseTable.get().qualifiedName().toString());

            Map<String, Long> usageCounts = new LinkedHashMap<>();
            documentTable.columns().forEach(column -> usageCounts.put(
                    column.name().normalized(),
                    metadata.frequency(MetadataComparisonValidator.path(documentTable, column))));

            byte[] workbook = compareExcelWriter.write(
                    documentTable, databaseTable.get(), usageCounts, platform.name(), dialect);
            String fileName = schemaName + "." + tableName
                    + "_compare_" + timestamp
                    + "." + platform.commandLineName() + ".xlsx";
            Path workbookPath = output.resolve(fileName);
            Files.write(workbookPath, workbook);
            LOGGER.info("[{}] Comparison workbook generated: {}", platform.name(), workbookPath.getFileName());
        }
    }

    private static void unzipSafely(MultipartFile file, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    private static byte[] zipDirectory(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(directory.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void requireExtension(MultipartFile file, String extension) {
        String name = safeName(file.getOriginalFilename(), "upload");
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) throw new IllegalArgumentException("Expected " + extension + " file");
    }

    private static String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        return Path.of(name).getFileName().toString();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
