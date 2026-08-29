package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.specification.recovery.IdentifierSanitizer;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
import com.behsazan.schemaforge.specification.recovery.RecoveryResult;
import com.behsazan.schemaforge.specification.validation.IdentifierValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts the legacy Word table extractor to the canonical SchemaForge model.
 *
 * <p>The extractor DTOs in this package are an implementation detail. The only
 * model returned to the rest of SchemaForge is {@link DatabaseSchema}; therefore
 * standard and legacy Word documents share the same table, column, key, index
 * and foreign-key classes and the same generation pipeline.</p>
 */
public final class LegacyWordSpecificationParser {
    public static final String PARSER_VERSION = "0.7.2";
    private static final long DEFAULT_MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?i)^\\s*([A-Z][A-Z0-9_ ]*?)(?:\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*$");
    private static final Pattern ORDERED_TOKEN = Pattern.compile(
            "(?i)^(PK|PFK|UK|UQ|UIX|IX|IDX|INDEX|I|X)(\\d*)(?:[_:,](\\d+))?$");
    private static final Pattern RAW_CHARACTER_DECLARATION = Pattern.compile(
            "(?i)^\\s*(?:VARCHAR2?|VCHAR|VCHR|VC|CHARACTER|CHAR|C)\\s*\\(?\\s*(\\d+)\\s*\\)?\\s*$");
    private static final Pattern POSITIVE_INTEGER_CELL = Pattern.compile("^\\d+$");

    private final WordTableParser parser;
    private final Db2SysColumnsFileCatalog db2MetadataCatalog;
    private final LegacyDefaultValueNormalizer defaultValueNormalizer = new LegacyDefaultValueNormalizer();
    private final IdentifierValidator identifierValidator = new IdentifierValidator();
    private final IdentifierSanitizer identifierSanitizer = new IdentifierSanitizer();

    public LegacyWordSpecificationParser() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    public LegacyWordSpecificationParser(long maxFileBytes) {
        this(WordTableParser.create(maxFileBytes), null);
    }

    public static LegacyWordSpecificationParser withDb2SysColumns(Path file) {
        return new LegacyWordSpecificationParser(
                WordTableParser.create(DEFAULT_MAX_FILE_BYTES),
                new Db2SysColumnsFileCatalog(file));
    }

    LegacyWordSpecificationParser(WordTableParser parser) {
        this(parser, null);
    }

    LegacyWordSpecificationParser(WordTableParser parser, Db2SysColumnsFileCatalog db2MetadataCatalog) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.db2MetadataCatalog = db2MetadataCatalog;
    }

    public boolean supports(Path document) {
        if (document == null || document.getFileName() == null) {
            return false;
        }
        String name = document.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx");
    }

    public DatabaseSchema parse(Path document, String schemaName) {
        Objects.requireNonNull(document, "document must not be null");
        Path parent = document.toAbsolutePath().normalize().getParent();
        return parse(parent, document, schemaName);
    }

    public DatabaseSchema parse(Path inputRoot, Path document, String schemaName) {
        Objects.requireNonNull(document, "document must not be null");
        Path normalizedDocument = document.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedDocument)) {
            throw new IllegalArgumentException("Legacy Word document does not exist: " + normalizedDocument);
        }
        if (!supports(normalizedDocument)) {
            throw new IllegalArgumentException("Legacy Word document must be DOC or DOCX: " + normalizedDocument);
        }

        String resolvedSchema = identifierValidator.requireValid(
                schemaName, "schema", normalizedDocument.getFileName().toString());
        WordTableParseResult result = parser.parse(inputRoot, normalizedDocument);
        if (!result.acceptedTableDocument() || result.table() == null || result.columns().isEmpty()) {
            String detail = firstNonBlank(result.errorMessage(), result.status().name());
            throw new IllegalArgumentException("No legacy table definition was accepted from "
                    + normalizedDocument.getFileName() + ": " + detail);
        }

        List<String> adapterWarnings = new ArrayList<>();
        String tableName = recoverIdentifier(
                result.table().technicalName(), "table", normalizedDocument.getFileName().toString(), adapterWarnings);
        Table.Builder table = Table.builder(resolvedSchema, tableName);
        if (result.table().persianNameConfidence() == MetadataConfidence.TRUSTED
                && hasText(result.table().persianName())) {
            table.persianName(result.table().persianName().trim());
        }

        List<CanonicalColumn> canonicalColumns = new ArrayList<>();
        for (ParsedWordColumn sourceColumn : result.columns().stream()
                .sorted(Comparator.comparingInt(ParsedWordColumn::sequence))
                .toList()) {
            canonicalColumns.add(toCanonicalColumn(sourceColumn, normalizedDocument, resolvedSchema, tableName, adapterWarnings));
        }
        canonicalColumns.forEach(column -> table.addColumn(column.column()));

        addPrimaryKey(table, tableName, canonicalColumns);
        addUniqueKeys(table, tableName, canonicalColumns);
        addIndexes(table, tableName, canonicalColumns);
        addForeignKeys(table, resolvedSchema, tableName, canonicalColumns, adapterWarnings);

        DatabaseSchema.Builder schema = DatabaseSchema.builder(resolvedSchema)
                .metadata("source.fileName", normalizedDocument.getFileName().toString())
                .metadata("source.relativePath", emptyToDefault(result.relativePath(), normalizedDocument.getFileName().toString()))
                .metadata("source.parser", "LEGACY_WORD")
                .metadata("source.parserClass", getClass().getName())
                .metadata("source.parserVersion", PARSER_VERSION)
                .metadata("source.schemaSource", "REST_PARAMETER")
                .metadata("source.word.declaredFormat", result.declaredFormat().name())
                .metadata("source.word.detectedFormat", result.detectedFormat().name())
                .metadata("source.word.formatMismatch", Boolean.toString(result.formatMismatch()))
                .metadata("source.word.parseStatus", result.status().name())
                .metadata("source.word.issueCount", Integer.toString(result.issues().size()))
                .metadata("source.word.fileSize", Long.toString(result.fileSize()))
                .metadata("source.word.durationMillis", Long.toString(result.durationMillis()))
                .addTable(table.build());

        addMetadata(schema, "source.word.documentType", result.table().documentType());
        addMetadata(schema, "source.word.systemName", result.table().systemName());
        addMetadata(schema, "source.word.entityName", result.table().entityName());
        addMetadata(schema, "source.word.createdDateRaw", result.table().createdDateRaw());
        addMetadata(schema, "source.word.modifiedDateRaw", result.table().modifiedDateRaw());
        addMetadata(schema, "source.word.persianNameConfidence", result.table().persianNameConfidence().name());
        addMetadata(schema, "source.word.persianNameSource", result.table().persianNameSource());
        if (db2MetadataCatalog != null) {
            schema.metadata("source.db2Metadata.file", db2MetadataCatalog.sourceFileName());
            schema.metadata("source.db2Metadata.sourceRows", Integer.toString(db2MetadataCatalog.sourceRows()));
            schema.metadata("source.db2Metadata.usableColumns", Integer.toString(db2MetadataCatalog.usableColumns()));
            schema.metadata("source.db2Metadata.ambiguousColumns", Integer.toString(db2MetadataCatalog.ambiguousColumns()));
        }

        List<String> issues = result.issues().stream().map(LegacyWordSpecificationParser::renderIssue).toList();
        if (!issues.isEmpty()) {
            schema.metadata("source.word.issues", String.join(System.lineSeparator(), issues));
        }
        if (!adapterWarnings.isEmpty()) {
            schema.metadata("recovery.warningCount", Integer.toString(adapterWarnings.size()));
            schema.metadata("recovery.warnings", String.join(System.lineSeparator(), adapterWarnings));
        } else {
            schema.metadata("recovery.warningCount", "0");
        }
        return schema.build();
    }

    private CanonicalColumn toCanonicalColumn(
            ParsedWordColumn source,
            Path document,
            String schemaName,
            String tableName,
            List<String> warnings) {
        String columnName = recoverIdentifier(
                source.technicalName(), "column", document.getFileName().toString(), warnings);
        DataType metadataType = metadataType(schemaName, tableName, columnName);
        String metadataStatus = metadataStatus(schemaName, tableName, columnName);
        DataType dataType = toDataType(source, columnName, metadataType, metadataStatus, warnings);
        boolean nullable = !Boolean.TRUE.equals(source.mandatory());
        if (source.mandatory() == null) {
            warnings.add("NULLABILITY_NOT_SPECIFIED|column=" + columnName + "|fallback=NULLABLE");
        }
        String description = source.persianNameConfidence() == MetadataConfidence.TRUSTED
                && hasText(source.persianName())
                ? source.persianName().trim()
                : "";
        LegacyDefaultValueNormalizer.Result defaultResult =
                defaultValueNormalizer.normalize(source.defaultValue(), dataType);
        if (defaultResult.changed()) {
            String code = defaultResult.dropped()
                    ? "LEGACY_DEFAULT_DROPPED"
                    : "LEGACY_DEFAULT_NORMALIZED";
            warnings.add(code + "|column=" + columnName
                    + "|reason=" + defaultResult.reason()
                    + "|raw=" + safe(defaultResult.rawValue())
                    + "|normalized=" + safe(defaultResult.expression()));
        }
        Column column = new Column(
                Identifier.of(columnName),
                dataType,
                nullable,
                new DefaultValue(defaultResult.expression()),
                new Description(description),
                false,
                source.sequence() > 0 ? source.sequence() : null,
                null);
        return new CanonicalColumn(source, column, columnName);
    }

    private DataType toDataType(ParsedWordColumn source, String columnName, DataType metadataType,
                                String metadataStatus, List<String> warnings) {
        TypeSelection selection = selectType(source);
        boolean metadataSelection = false;
        if (selection == null) {
            RawTypeRecovery recovery = recoverTypeFromRawCells(source);
            if (recovery != null) {
                selection = new TypeSelection(recovery.type(), false);
                warnings.add(recovery.code() + "|column=" + columnName
                        + "|logical=" + safe(source.logicalTypeRaw())
                        + "|physical=" + safe(source.physicalTypeRaw())
                        + "|evidence=" + safe(recovery.evidence()));
            }
        }
        if (selection == null && metadataType != null && metadataTypeRecoveryEligible(source)) {
            String declaration = metadataDeclaration(metadataType);
            selection = new TypeSelection(declaration, false);
            metadataSelection = true;
            warnings.add("DB2_METADATA_DATATYPE_RECOVERY|column=" + columnName
                    + "|logical=" + safe(source.logicalTypeRaw())
                    + "|physical=" + safe(source.physicalTypeRaw())
                    + "|metadata=" + declaration);
        }
        if (selection == null) {
            throw new IllegalArgumentException("No reliable SQL data type for legacy column " + columnName
                    + " (logical='" + safe(source.logicalTypeRaw())
                    + "', physical='" + safe(source.physicalTypeRaw()) + "')"
                    + metadataDiagnosticSuffix(metadataStatus));
        }
        if (selection.physicalFallback()) {
            warnings.add("DATATYPE_PHYSICAL_FALLBACK|column=" + columnName
                    + "|logical=" + safe(source.logicalTypeRaw())
                    + "|physical=" + safe(source.physicalTypeRaw()));
        }

        Matcher matcher = TYPE_DECLARATION.matcher(selection.type().trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported legacy data type for column "
                    + columnName + ": " + selection.type());
        }
        String base = canonicalTypeName(matcher.group(1));
        Integer declarationFirst = integer(matcher.group(2));
        Integer declarationSecond = integer(matcher.group(3));

        LengthValueParser.ParsedLength logicalLength = LengthValueParser.parse(source.lengthRaw());
        LengthValueParser.ParsedLength physicalLength = LengthValueParser.parse(source.physicalLengthRaw());
        LengthValueParser.ParsedLength parsedLength = metadataSelection
                ? LengthValueParser.parse("")
                : (selection.physicalFallback() ? physicalLength : logicalLength);
        Integer length = firstNonNull(parsedLength.length(), declarationSecond == null ? declarationFirst : null);
        Integer precision = firstNonNull(
                parsedLength.precision(),
                firstNonNull(parsedLength.length(), declarationFirst));
        Integer scale = firstNonNull(parsedLength.scale(), declarationSecond);

        if (isCharacter(base)) {
            if (length == null && selection.physicalFallback() && logicalLength.length() != null) {
                length = logicalLength.length();
                warnings.add("LEGACY_CHARACTER_LENGTH_LOGICAL_FALLBACK|column=" + columnName
                        + "|logicalType=" + safe(source.logicalTypeRaw())
                        + "|logicalLength=" + safe(source.lengthRaw())
                        + "|physicalType=" + safe(source.physicalTypeRaw()));
            }
            if (length == null && !selection.physicalFallback()) {
                String physicalBase = trustedPhysicalBase(source);
                if (physicalBase != null && isCharacter(physicalBase) && physicalLength.length() != null) {
                    length = physicalLength.length();
                    warnings.add("LEGACY_CHARACTER_LENGTH_PHYSICAL_FALLBACK|column=" + columnName
                            + "|logicalType=" + safe(source.logicalTypeRaw())
                            + "|physicalType=" + safe(source.physicalTypeRaw())
                            + "|physicalLength=" + safe(source.physicalLengthRaw()));
                } else if (source.physicalTypeConfidence() == DataTypeConfidence.NOT_PRESENT
                        && physicalLength.length() != null) {
                    length = physicalLength.length();
                    warnings.add("LEGACY_CHARACTER_LENGTH_PHYSICAL_LENGTH_ONLY_FALLBACK|column=" + columnName
                            + "|logicalType=" + safe(source.logicalTypeRaw())
                            + "|physicalLength=" + safe(source.physicalLengthRaw()));
                }
            }
            if (length == null) {
                RawLengthRecovery recovery = recoverCharacterLengthFromRawCells(source, selection);
                if (recovery != null) {
                    length = recovery.length();
                    warnings.add(recovery.code() + "|column=" + columnName
                            + "|logicalType=" + safe(source.logicalTypeRaw())
                            + "|evidence=" + safe(recovery.evidence()));
                }
            }
            if (length == null && metadataType != null && metadataTypeRecoveryEligible(source)
                    && metadataCharacterLengthCompatible(base, metadataType)) {
                length = metadataType.length();
                warnings.add("DB2_METADATA_CHARACTER_LENGTH_RECOVERY|column=" + columnName
                        + "|type=" + base
                        + "|metadata=" + metadataDeclaration(metadataType));
            }
            if (length == null) {
                throw new IllegalArgumentException("Character length is missing for legacy column "
                        + columnName + " type " + base + metadataDiagnosticSuffix(metadataStatus));
            }
            LengthSemantics semantics = (base.equals("NCHAR") || base.equals("NVARCHAR")
                    || base.equals("NVARCHAR2"))
                    ? LengthSemantics.DEFAULT
                    : LengthSemantics.CHAR;
            return new DataType(Identifier.of(base), length, semantics, null, null);
        }
        if (isRaw(base)) {
            if (length == null) {
                throw new IllegalArgumentException("Binary length is missing for legacy column "
                        + columnName + " type " + base);
            }
            return new DataType(Identifier.of(base), length, LengthSemantics.DEFAULT, null, null);
        }
        if (base.equals("NUMBER") || base.equals("DECIMAL") || base.equals("NUMERIC")) {
            if (precision != null && precision <= 0) {
                warnings.add("LEGACY_NUMERIC_NONPOSITIVE_PRECISION_IGNORED|column=" + columnName
                        + "|type=" + base
                        + "|rawLength=" + safe(selection.physicalFallback()
                                ? source.physicalLengthRaw()
                                : source.lengthRaw()));
                precision = null;
                scale = null;
            }
            if (precision != null && scale != null && scale > precision
                    && metadataType != null && metadataTypeRecoveryEligible(source)
                    && metadataNumericCompatible(metadataType)) {
                precision = metadataType.precision();
                scale = metadataType.scale();
                warnings.add("DB2_METADATA_NUMERIC_PRECISION_SCALE_RECOVERY|column=" + columnName
                        + "|type=" + base
                        + "|metadata=" + metadataDeclaration(metadataType));
            }
            if (precision == null) {
                return DataType.simple(base);
            }
            if (scale != null && scale > precision) {
                throw new IllegalArgumentException("scale must not exceed precision"
                        + metadataDiagnosticSuffix(metadataStatus));
            }
            return DataType.numeric(base, precision, scale);
        }
        if (base.equals("TIMESTAMP")) {
            // In legacy table specifications the separate Length column is a display/storage
            // length (commonly 10, 12, 15 or 26), not SQL fractional-seconds precision.
            // Only an explicit type declaration such as TIMESTAMP(6) carries temporal precision.
            if (declarationFirst != null) {
                return DataType.numeric(base, declarationFirst, null);
            }
            if (hasText(parsedLength.normalized())) {
                warnings.add("LEGACY_TEMPORAL_LENGTH_IGNORED|column=" + columnName
                        + "|type=" + base
                        + "|rawLength=" + safe(selection.physicalFallback()
                                ? source.physicalLengthRaw()
                                : source.lengthRaw()));
            }
            return DataType.simple(base);
        }
        return DataType.simple(base);
    }


    private RawLengthRecovery recoverCharacterLengthFromRawCells(
            ParsedWordColumn source, TypeSelection selection) {
        if (source.rawCells() == null || source.rawCells().isEmpty()) {
            return null;
        }

        int logicalTypeCell = findLogicalTypeCell(source);
        if (logicalTypeCell >= 0) {
            for (int index = logicalTypeCell + 1; index < source.rawCells().size(); index++) {
                String cell = TextNormalizer.cleanCell(source.rawCells().get(index));
                Matcher declaration = RAW_CHARACTER_DECLARATION.matcher(cell);
                if (declaration.matches()) {
                    Integer value = integer(declaration.group(1));
                    if (value != null && value > 0) {
                        return new RawLengthRecovery(
                                value,
                                "LEGACY_CHARACTER_LENGTH_INLINE_DECLARATION_RECOVERY",
                                cell);
                    }
                }
            }
        }

        RawLengthRecovery adjacent = recoverAdjacentCharacterLength(source.rawCells(), selection.type());
        if (adjacent != null) {
            return adjacent;
        }

        Integer onlyNumeric = onlyPositiveIntegerCell(source.rawCells());
        if (!selection.physicalFallback() && logicalTypeCell >= 0 && onlyNumeric != null
                && logicalTypeCell + 2 < source.rawCells().size()) {
            String immediate = TextNormalizer.cleanCell(source.rawCells().get(logicalTypeCell + 1));
            String shifted = TextNormalizer.cleanCell(source.rawCells().get(logicalTypeCell + 2));
            if (immediate.isBlank() && Integer.toString(onlyNumeric).equals(shifted)) {
                return new RawLengthRecovery(
                        onlyNumeric,
                        "LEGACY_CHARACTER_LENGTH_SHIFTED_CELL_RECOVERY",
                        "shifted-cell:" + shifted);
            }
        }
        return null;
    }

    /**
     * Recovers a missing character length only from an explicit type/length adjacency in the
     * original Word row. Unlike the Recovery2 rule, this does not require the row to contain a
     * single numeric cell; index ordinals and other numeric metadata may coexist in the row.
     * Ambiguous rows that expose different adjacent lengths remain unresolved.
     */
    private RawLengthRecovery recoverAdjacentCharacterLength(List<String> rawCells, String selectedType) {
        String selectedBase = canonicalTypeName(selectedType);
        Integer recovered = null;
        String evidence = null;

        for (int typeIndex = 0; typeIndex < rawCells.size(); typeIndex++) {
            String rawType = TextNormalizer.cleanCell(rawCells.get(typeIndex));
            String normalized = trustedCharacterType(rawType);
            if (normalized == null || !canonicalTypeName(normalized).equals(selectedBase)) {
                continue;
            }
            // Standard/shifted layouts place length immediately to the right of the type.
            // Consult the left cell only when the right cell carries no numeric evidence; this
            // still supports reversed RTL layouts without confusing a numeric key/index cell
            // on the left with the actual character length on the right.
            int candidate = typeIndex + 1;
            Integer value = positiveIntegerCell(rawCells, candidate);
            if (value == null && candidate < rawCells.size()
                    && TextNormalizer.cleanCell(rawCells.get(candidate)).isBlank()) {
                int shiftedCandidate = typeIndex + 2;
                Integer shiftedValue = positiveIntegerCell(rawCells, shiftedCandidate);
                if (shiftedValue != null) {
                    candidate = shiftedCandidate;
                    value = shiftedValue;
                }
            }
            if (value == null) {
                candidate = typeIndex - 1;
                value = positiveIntegerCell(rawCells, candidate);
            }
            if (value == null) {
                continue;
            }
            if (recovered != null && !recovered.equals(value)) {
                return null;
            }
            recovered = value;
            evidence = rawType + "@" + typeIndex + ":" + value + "@" + candidate;
        }

        return recovered == null ? null : new RawLengthRecovery(
                recovered,
                "LEGACY_CHARACTER_LENGTH_ADJACENT_TYPE_RECOVERY",
                evidence);
    }

    private String trustedCharacterType(String raw) {
        LegacyDataTypeNormalizer.TypeStatus sourceStatus = LegacyDataTypeNormalizer.sourceTypeStatus(raw);
        if (sourceStatus == LegacyDataTypeNormalizer.TypeStatus.TRUSTED) {
            String normalized = LegacyDataTypeNormalizer.normalize(raw);
            if (hasText(normalized) && isCharacter(canonicalTypeName(normalized))) {
                return normalized;
            }
        }
        LegacyDataTypeNormalizer.TypeStatus physicalStatus = LegacyDataTypeNormalizer.db2TypeStatus(raw);
        if (physicalStatus == LegacyDataTypeNormalizer.TypeStatus.TRUSTED) {
            String normalized = LegacyDataTypeNormalizer.normalizeDb2(raw);
            if (hasText(normalized) && isCharacter(canonicalTypeName(normalized))) {
                return normalized;
            }
        }
        return null;
    }

    private Integer positiveIntegerCell(List<String> rawCells, int index) {
        if (index < 0 || index >= rawCells.size()) {
            return null;
        }
        String cell = TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(rawCells.get(index)));
        if (!POSITIVE_INTEGER_CELL.matcher(cell).matches()) {
            return null;
        }
        Integer value = integer(cell);
        return value != null && value > 0 ? value : null;
    }

    private int findLogicalTypeCell(ParsedWordColumn source) {
        String rawLogicalType = TextNormalizer.cleanCell(source.logicalTypeRaw());
        if (rawLogicalType.isBlank()) {
            return -1;
        }
        int match = -1;
        for (int index = 0; index < source.rawCells().size(); index++) {
            if (!rawLogicalType.equalsIgnoreCase(TextNormalizer.cleanCell(source.rawCells().get(index)))) {
                continue;
            }
            if (match >= 0) {
                return -1;
            }
            match = index;
        }
        return match;
    }

    private Integer onlyPositiveIntegerCell(List<String> rawCells) {
        Integer result = null;
        for (String rawCell : rawCells) {
            String cell = TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(rawCell));
            if (!POSITIVE_INTEGER_CELL.matcher(cell).matches()) {
                continue;
            }
            Integer value = integer(cell);
            if (value == null || value <= 0) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = value;
        }
        return result;
    }

    /**
     * Looks for a single unambiguous SQL type token displaced inside the known type/metadata
     * region of the raw Word row. Single-letter N/C/S tokens are deliberately excluded here:
     * their meaning depends on the source column and therefore cannot be recovered safely from
     * an arbitrary displaced cell.
     */
    private RawTypeRecovery recoverTypeFromRawCells(ParsedWordColumn source) {
        if (source.rawCells() == null || source.rawCells().isEmpty()) {
            return null;
        }
        String logicalRaw = TextNormalizer.cleanCell(source.logicalTypeRaw());
        String physicalRaw = TextNormalizer.cleanCell(source.physicalTypeRaw());
        String recovered = null;
        String evidence = null;

        for (int index : new int[] {2, 3, 7, 8}) {
            if (index >= source.rawCells().size()) {
                continue;
            }
            String cell = TextNormalizer.cleanCell(source.rawCells().get(index));
            if (cell.isBlank()
                    || ((index == 2 || index == 3) && cell.equalsIgnoreCase(logicalRaw))
                    || (!physicalRaw.isBlank() && cell.equalsIgnoreCase(physicalRaw))) {
                continue;
            }
            String normalized;
            if (isContextualPhysicalTypeSlot(source, index)
                    && LegacyDataTypeNormalizer.db2TypeStatus(cell)
                    == LegacyDataTypeNormalizer.TypeStatus.TRUSTED) {
                // These are the confirmed physical-type slots in the standard/dual/shifted
                // legacy layouts. Here DB2-only aliases such as S=SMALLINT and C=CHAR are
                // contextual evidence, not logical-type guesses.
                normalized = LegacyDataTypeNormalizer.normalizeDb2(cell);
            } else {
                if (LegacyDataTypeNormalizer.sourceTypeStatus(cell)
                        != LegacyDataTypeNormalizer.TypeStatus.TRUSTED) {
                    continue;
                }
                normalized = LegacyDataTypeNormalizer.normalize(cell);
                if (!hasText(normalized)) {
                    continue;
                }
                String base = normalized.replaceAll("\\s*\\(.*$", "")
                        .trim().toUpperCase(Locale.ROOT);
                if (base.equals("N") || base.equals("C") || base.equals("S")) {
                    continue;
                }
            }
            if (!hasText(normalized)) {
                continue;
            }
            if (recovered != null && !recovered.equalsIgnoreCase(normalized)) {
                return null;
            }
            recovered = normalized;
            evidence = "cell[" + index + "]=" + cell;
        }
        return recovered == null ? null : new RawTypeRecovery(
                recovered,
                "LEGACY_DATATYPE_DISPLACED_CELL_RECOVERY",
                evidence);
    }

    private boolean isContextualPhysicalTypeSlot(ParsedWordColumn source, int index) {
        String logicalRaw = TextNormalizer.cleanCell(source.logicalTypeRaw());
        if (logicalRaw.isBlank()) {
            return false;
        }
        int logicalIndex = -1;
        if (source.rawCells().size() > 2
                && logicalRaw.equalsIgnoreCase(TextNormalizer.cleanCell(source.rawCells().get(2)))) {
            logicalIndex = 2;
        } else if (source.rawCells().size() > 3
                && logicalRaw.equalsIgnoreCase(TextNormalizer.cleanCell(source.rawCells().get(3)))) {
            logicalIndex = 3;
        }

        if (logicalIndex == 3) {
            return index == 8;
        }
        if (logicalIndex != 2) {
            return false;
        }
        if (index == 8) {
            return true;
        }
        if (index != 7) {
            return false;
        }
        if (source.rawCells().size() <= 8) {
            return true;
        }
        String possibleLength = TextNormalizer.cleanCell(source.rawCells().get(8));
        if (possibleLength.isBlank()) {
            return true;
        }
        LengthValueParser.ParsedLength parsed = LengthValueParser.parse(possibleLength);
        return !parsed.ambiguous() && !parsed.normalized().isBlank();
    }

    private DataType metadataType(String schemaName, String tableName, String columnName) {
        if (db2MetadataCatalog == null) return null;
        return db2MetadataCatalog.findType(schemaName, tableName, columnName).orElse(null);
    }

    private String metadataStatus(String schemaName, String tableName, String columnName) {
        if (db2MetadataCatalog == null) return null;
        return db2MetadataCatalog.lookupStatus(schemaName, tableName, columnName).name();
    }

    private String metadataDiagnosticSuffix(String metadataStatus) {
        return metadataStatus == null ? "" : " [db2Metadata=" + metadataStatus + "]";
    }

    static boolean metadataTypeRecoveryEligible(ParsedWordColumn source) {
        String fieldRaw = TextNormalizer.cleanCell(source.technicalNameRaw());
        // Recovery10: an exact schema+table+column metadata hit is authoritative evidence
        // for a missing/unreliable datatype even when the Word datatype cells themselves
        // are merged (for example "N C" or "I VC").  The only structural case we
        // still reject is a merged technical field name because then the metadata key is
        // not a trustworthy one-to-one column identity.
        return !looksStructurallyMerged(fieldRaw);
    }

    private static boolean looksStructurallyMerged(String value) {
        if (value == null || value.isBlank()) return false;
        String cleaned = value.trim();
        if (cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0 || cleaned.indexOf('\u0007') >= 0) {
            return true;
        }
        return cleaned.matches(".*\\s+.*");
    }

    static boolean metadataCharacterLengthCompatible(String wordBase, DataType metadataType) {
        if (metadataType == null || metadataType.length() == null || metadataType.length() <= 0) {
            return false;
        }
        String word = canonicalTypeName(wordBase);
        String metadata = canonicalTypeName(metadataBase(metadataType));
        if (word.equals(metadata)) {
            return true;
        }
        // DB2 physical CHAR/VARCHAR both provide reliable character-width evidence for
        // the canonical single-byte character family.  Likewise GRAPHIC/VARGRAPHIC map
        // to NCHAR/NVARCHAR.  We recover only the missing length; the valid Word logical
        // datatype itself is not overwritten.
        return sameCharacterLengthFamily(word, metadata);
    }

    private static boolean sameCharacterLengthFamily(String left, String right) {
        return (isBasicCharacter(left) && isBasicCharacter(right))
                || (isNationalCharacter(left) && isNationalCharacter(right));
    }

    private static boolean isBasicCharacter(String type) {
        return type.equals("CHAR") || type.equals("VARCHAR");
    }

    private static boolean isNationalCharacter(String type) {
        return type.equals("NCHAR") || type.equals("NVARCHAR");
    }

    private boolean metadataNumericCompatible(DataType metadataType) {
        if (metadataType.precision() == null || metadataType.precision() <= 0) return false;
        if (metadataType.scale() != null && metadataType.scale() > metadataType.precision()) return false;
        String base = canonicalTypeName(metadataBase(metadataType));
        return base.equals("NUMBER") || base.equals("DECIMAL") || base.equals("NUMERIC");
    }

    private String metadataDeclaration(DataType metadataType) {
        String base = metadataBase(metadataType);
        if (metadataType.length() != null) {
            return base + "(" + metadataType.length() + ")";
        }
        if (metadataType.precision() != null) {
            return base + "(" + metadataType.precision()
                    + (metadataType.scale() == null ? "" : "," + metadataType.scale()) + ")";
        }
        return base;
    }

    private static String metadataBase(DataType metadataType) {
        String name = metadataType.name().normalized();
        return switch (name) {
            case "DB2_DATE" -> "DATE";
            case "DB2_ROWID" -> "ROWID";
            case "TIMESTAMP_WITH_TIME_ZONE" -> "TIMESTAMP WITH TIME ZONE";
            default -> metadataType.name().value();
        };
    }

    private record RawLengthRecovery(int length, String code, String evidence) {
    }

    private record RawTypeRecovery(String type, String code, String evidence) {
    }

    private String trustedPhysicalBase(ParsedWordColumn source) {
        if (source.physicalTypeConfidence() != DataTypeConfidence.TRUSTED
                || !hasText(source.physicalType())) {
            return null;
        }
        Matcher matcher = TYPE_DECLARATION.matcher(source.physicalType().trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        return canonicalTypeName(matcher.group(1));
    }

    private TypeSelection selectType(ParsedWordColumn source) {
        if (source.logicalTypeConfidence() == DataTypeConfidence.TRUSTED
                && hasText(source.logicalType())) {
            String logical = source.logicalType().trim().toUpperCase(Locale.ROOT);
            if (logical.equals("N")) {
                return new TypeSelection("NUMBER", false);
            }
            if (logical.equals("C")) {
                return new TypeSelection("VARCHAR", false);
            }
            if (!logical.equals("S")) {
                return new TypeSelection(source.logicalType(), false);
            }
        }
        if (source.physicalTypeConfidence() == DataTypeConfidence.TRUSTED
                && hasText(source.physicalType())) {
            return new TypeSelection(source.physicalType(), true);
        }
        return null;
    }

    private void addPrimaryKey(Table.Builder table, String tableName, List<CanonicalColumn> columns) {
        List<OrderedColumn> members = columns.stream()
                .filter(column -> column.source().primaryKey() || hasTokenPrefix(column.source().keys(), "PFK"))
                .map(column -> new OrderedColumn(column.name(), primaryKeyOrder(column.source()), column.source().sequence()))
                .sorted(ORDERED_COLUMN_COMPARATOR)
                .toList();
        if (!members.isEmpty()) {
            table.primaryKey(new PrimaryKey(
                    Identifier.of(objectName("PK", tableName, null)),
                    members.stream().map(member -> Identifier.of(member.name())).toList()));
        }
    }

    private void addUniqueKeys(Table.Builder table, String tableName, List<CanonicalColumn> columns) {
        Map<String, List<OrderedColumn>> groups = new LinkedHashMap<>();
        for (CanonicalColumn column : columns) {
            for (String token : column.source().keys()) {
                Token parsed = parseToken(token);
                if (parsed == null || !(parsed.prefix().equals("UK") || parsed.prefix().equals("UQ"))) {
                    continue;
                }
                groups.computeIfAbsent(parsed.group(), ignored -> new ArrayList<>())
                        .add(new OrderedColumn(column.name(), parsed.position(), column.source().sequence()));
            }
        }
        groups.forEach((group, members) -> table.addUniqueKey(new UniqueKey(
                Identifier.of(objectName("UK", tableName, group)),
                members.stream().sorted(ORDERED_COLUMN_COMPARATOR)
                        .map(member -> Identifier.of(member.name())).toList())));
    }

    private void addIndexes(Table.Builder table, String tableName, List<CanonicalColumn> columns) {
        Map<String, IndexGroup> groups = new LinkedHashMap<>();
        for (CanonicalColumn column : columns) {
            for (String token : column.source().indexes()) {
                Token parsed = parseToken(token);
                if (parsed == null || !isIndexPrefix(parsed.prefix())) {
                    continue;
                }
                IndexGroup group = groups.computeIfAbsent(parsed.group(), ignored ->
                        new IndexGroup(parsed.prefix().equals("UIX"), new ArrayList<>()));
                group.members().add(new OrderedColumn(
                        column.name(), parsed.position(), column.source().sequence()));
            }
        }
        groups.forEach((groupName, group) -> table.addIndex(new Index(
                Identifier.of(objectName(group.unique() ? "UIX" : "IX", tableName, groupName)),
                group.members().stream().sorted(ORDERED_COLUMN_COMPARATOR)
                        .map(member -> new IndexColumn(Identifier.of(member.name()), SortDirection.ASC))
                        .toList(),
                group.unique() ? IndexType.UNIQUE : IndexType.NORMAL,
                Description.empty())));
    }

    private void addForeignKeys(
            Table.Builder table,
            String schemaName,
            String tableName,
            List<CanonicalColumn> columns,
            List<String> warnings) {
        for (CanonicalColumn column : columns) {
            if (!column.source().foreignKey()) {
                continue;
            }
            String reference = trimToNull(column.source().referencedTable());
            if (reference == null) {
                warnings.add("FK_REFERENCE_MISSING|column=" + column.name());
                continue;
            }
            ReferenceTable referencedTable;
            try {
                referencedTable = parseReferenceTable(reference, schemaName, warnings);
            } catch (IllegalArgumentException exception) {
                warnings.add("FK_REFERENCE_INVALID|column=" + column.name()
                        + "|value=" + safe(reference) + "|message=" + safe(exception.getMessage()));
                continue;
            }
            table.addForeignKey(new ForeignKey(
                    Identifier.of(objectName("FK", tableName, column.name())),
                    List.of(Identifier.of(column.name())),
                    QualifiedName.of(referencedTable.schema(), referencedTable.table()),
                    List.of(Identifier.of(column.name())),
                    ReferentialAction.NO_ACTION,
                    ReferentialAction.NO_ACTION,
                    false,
                    false,
                    true,
                    referencedTable.schemaExplicit()));
        }
    }

    private ReferenceTable parseReferenceTable(String raw, String defaultSchema, List<String> warnings) {
        String cleaned = raw.trim();
        String[] parts = cleaned.split("\\.");
        if (parts.length == 1) {
            return new ReferenceTable(defaultSchema,
                    recoverIdentifier(parts[0], "referenced table", null, warnings), false);
        }
        if (parts.length == 2) {
            return new ReferenceTable(
                    recoverIdentifier(parts[0], "referenced schema", null, warnings),
                    recoverIdentifier(parts[1], "referenced table", null, warnings), true);
        }
        throw new IllegalArgumentException("Expected TABLE or SCHEMA.TABLE");
    }

    private String recoverIdentifier(String raw, String objectType, String sourceName, List<String> warnings) {
        RecoveryResult recovered = identifierSanitizer.sanitize(raw, objectType);
        warnings.addAll(recovered.warnings());
        return identifierValidator.requireValid(recovered.value(), objectType, sourceName);
    }

    private static String canonicalTypeName(String rawBase) {
        String base = rawBase.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return switch (base) {
            case "VARCHAR", "VARCHAR2", "VCHAR", "VCHR" -> "VARCHAR";
            case "CHARACTER" -> "CHAR";
            case "GRAPHIC" -> "NCHAR";
            case "VARGRAPHIC" -> "NVARCHAR";
            case "DBCLOB" -> "NCLOB";
            case "DEC", "DECFLOAT" -> "DECIMAL";
            case "INT" -> "INTEGER";
            case "DOUBLE PRECISION", "FLOAT" -> "DOUBLE";
            case "BINARY", "VARBINARY" -> "RAW";
            case "BIT", "BOOLEAN" -> "NUMBER";
            case "TIME", "DATETIME" -> "TIMESTAMP";
            case "XML" -> "XMLTYPE";
            default -> base.replace(' ', '_');
        };
    }

    private static boolean isCharacter(String type) {
        return type.equals("VARCHAR") || type.equals("VARCHAR2")
                || type.equals("NVARCHAR") || type.equals("NVARCHAR2")
                || type.equals("CHAR") || type.equals("NCHAR");
    }

    private static boolean isRaw(String type) {
        return type.equals("RAW");
    }

    private static boolean hasTokenPrefix(List<String> values, String prefix) {
        return values != null && values.stream().filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.startsWith(prefix));
    }

    private static int primaryKeyOrder(ParsedWordColumn column) {
        return column.keys().stream()
                .map(LegacyWordSpecificationParser::parseToken)
                .filter(Objects::nonNull)
                .filter(token -> token.prefix().equals("PK") || token.prefix().equals("PFK"))
                .map(Token::position)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(column.sequence());
    }

    private static Token parseToken(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher matcher = ORDERED_TOKEN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
        String number = matcher.group(2);
        Integer explicitPosition = integer(matcher.group(3));

        if (prefix.equals("PFK")) {
            Integer position = explicitPosition != null ? explicitPosition : integer(number);
            return new Token(prefix, "PFK", position);
        }
        if (prefix.equals("PK")) {
            Integer position = explicitPosition != null ? explicitPosition : integer(number);
            return new Token(prefix, "PK", position);
        }
        String canonicalPrefix = switch (prefix) {
            case "IDX", "INDEX", "I", "X" -> "IX";
            default -> prefix;
        };
        String groupNumber = number == null || number.isBlank() ? "1" : number;
        String group = canonicalPrefix + groupNumber;
        return new Token(canonicalPrefix, group, explicitPosition);
    }

    private static boolean isIndexPrefix(String prefix) {
        return prefix.equals("IX") || prefix.equals("UIX");
    }

    private static String objectName(String prefix, String tableName, String suffix) {
        String raw = prefix + "_" + tableName + (hasText(suffix) ? "_" + suffix : "");
        String normalized = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_$#]", "_")
                .replaceAll("_+", "_");
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static String renderIssue(ParserIssue issue) {
        return issue.severity() + "|" + safe(issue.code())
                + "|field=" + safe(issue.fieldName())
                + "|row=" + (issue.sourceRowNumber() == null ? "" : issue.sourceRowNumber())
                + "|message=" + safe(issue.message())
                + "|raw=" + safe(issue.rawValue());
    }

    private static void addMetadata(DatabaseSchema.Builder builder, String key, String value) {
        if (hasText(value)) {
            builder.metadata(key, value.trim());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : second;
    }

    private static String emptyToDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static Integer integer(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static final Comparator<OrderedColumn> ORDERED_COLUMN_COMPARATOR =
            Comparator.comparing((OrderedColumn value) -> Optional.ofNullable(value.position()).orElse(Integer.MAX_VALUE))
                    .thenComparingInt(OrderedColumn::sequence)
                    .thenComparing(OrderedColumn::name);

    private record CanonicalColumn(ParsedWordColumn source, Column column, String name) {
    }

    private record OrderedColumn(String name, Integer position, int sequence) {
    }

    private record Token(String prefix, String group, Integer position) {
    }

    private record IndexGroup(boolean unique, List<OrderedColumn> members) {
    }

    private record TypeSelection(String type, boolean physicalFallback) {
    }

    private record ReferenceTable(String schema, String table, boolean schemaExplicit) {
    }
}
