package com.behsazan.schemaforge.specification.parser.legacy;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.FileResult;

/** Default thread-safe facade used by the SchemaForge application. */
final class SchemaForgeWordTableParser implements WordTableParser {
    private final long maxFileBytes;

    SchemaForgeWordTableParser(long maxFileBytes) {
        if (maxFileBytes <= 0L) {
            throw new IllegalArgumentException("maxFileBytes must be greater than zero");
        }
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public WordTableParseResult parse(Path document) {
        Path normalized = requireDocument(document);
        Path parent = normalized.getParent();
        return parse(parent == null ? normalized.toAbsolutePath().getParent() : parent, normalized);
    }

    @Override
    public WordTableParseResult parse(Path inputRoot, Path document) {
        Path normalizedDocument = requireDocument(document);
        Path normalizedRoot = inputRoot == null
                ? normalizedDocument.toAbsolutePath().getParent()
                : inputRoot.toAbsolutePath().normalize();
        if (normalizedRoot == null) {
            normalizedRoot = normalizedDocument.toAbsolutePath().getParent();
        }
        FileResult result = new DocTableExtractor().extract(
                normalizedRoot,
                normalizedDocument,
                maxFileBytes
        );
        return map(result);
    }

    private Path requireDocument(Path document) {
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        return document.toAbsolutePath().normalize();
    }

    private WordTableParseResult map(FileResult result) {
        ParsedWordTable table = result.metadata() == null ? null : new ParsedWordTable(
                result.metadata().documentType(),
                result.metadata().systemName(),
                result.metadata().tableName(),
                result.metadata().persianTableName(),
                map(PersianNameQuality.tableStatus(result)),
                result.metadata().persianTableNameSource(),
                result.metadata().entityName(),
                result.metadata().createdDateRaw(),
                result.metadata().modifiedDateRaw()
        );

        List<ParsedWordColumn> columns = result.columns().stream()
                .map(column -> map(result, column))
                .toList();
        List<ParserIssue> issues = result.warnings().stream()
                .map(this::map)
                .toList();

        return new WordTableParseResult(
                result.sourceFile(),
                result.relativePath(),
                map(result.declaredFormat()),
                map(result.sourceFormat()),
                result.formatMismatch(),
                result.fileSize(),
                result.durationMillis(),
                WordTableParseStatus.valueOf(result.status().name()),
                table,
                columns,
                issues,
                result.errorClass(),
                result.errorMessage(),
                result.processedAt()
        );
    }

    private ParsedWordColumn map(FileResult result, ColumnDefinition column) {
        LengthValueParser.ParsedLength logicalLength = LengthValueParser.parse(column.lengthRaw());
        LengthValueParser.ParsedLength physicalLength = LengthValueParser.parse(column.db2LengthRaw());
        FieldSupplementParser.Supplement supplement = FieldSupplementParser.parse(
                column.referenceOrDefaultRaw()
        );
        boolean primaryKey = containsPrefix(column.keys(), "PK");
        boolean foreignKey = containsPrefix(column.keys(), "FK")
                || containsPrefix(column.keys(), "PFK");

        return new ParsedWordColumn(
                column.sequence(),
                column.sourceTableIndex(),
                column.sourceRowIndex(),
                column.fieldName(),
                column.fieldNameRaw(),
                column.persianTitle(),
                map(PersianNameQuality.columnStatus(column)),
                column.typeRaw(),
                LegacyDataTypeNormalizer.normalize(column.typeRaw()),
                map(sourceTypeStatus(result, column)),
                column.lengthRaw(),
                logicalLength.normalized(),
                logicalLength.length(),
                logicalLength.precision(),
                logicalLength.scale(),
                logicalLength.ambiguous(),
                column.keyRaw(),
                column.keys(),
                primaryKey,
                foreignKey,
                column.indexRaw(),
                column.indexes(),
                column.mandatory(),
                column.db2TypeRaw(),
                LegacyDataTypeNormalizer.normalizeDb2(column.db2TypeRaw()),
                map(db2TypeStatus(result, column)),
                column.db2LengthRaw(),
                physicalLength.normalized(),
                supplement.referenceTable(),
                supplement.defaultValue(),
                supplement.description(),
                column.rawCells()
        );
    }

    private LegacyDataTypeNormalizer.TypeStatus sourceTypeStatus(
            FileResult result,
            ColumnDefinition column
    ) {
        if (hasWarning(result, column, "FIELD_TYPE_INVALID_SOURCE_TOKEN")) {
            return LegacyDataTypeNormalizer.TypeStatus.INVALID_SOURCE_TOKEN;
        }
        if (hasWarning(result, column, "FIELD_TYPE_UNRELIABLE")) {
            return LegacyDataTypeNormalizer.TypeStatus.UNRELIABLE;
        }
        return LegacyDataTypeNormalizer.sourceTypeStatus(column.typeRaw());
    }

    private LegacyDataTypeNormalizer.TypeStatus db2TypeStatus(
            FileResult result,
            ColumnDefinition column
    ) {
        if (hasWarning(result, column, "DB2_TYPE_INVALID_SOURCE_TOKEN")) {
            return LegacyDataTypeNormalizer.TypeStatus.INVALID_SOURCE_TOKEN;
        }
        return LegacyDataTypeNormalizer.db2TypeStatus(column.db2TypeRaw());
    }

    private boolean hasWarning(FileResult result, ColumnDefinition column, String code) {
        return result.warnings().stream().anyMatch(warning ->
                code.equals(warning.code())
                        && warning.fieldName() != null
                        && warning.fieldName().equalsIgnoreCase(column.fieldName())
                        && (warning.rowNumber() == null
                        || warning.rowNumber().equals(column.sourceRowIndex()))
        );
    }

    private boolean containsPrefix(List<String> values, String prefix) {
        if (values == null) {
            return false;
        }
        String upperPrefix = prefix.toUpperCase(Locale.ROOT);
        return values.stream()
                .map(value -> value == null ? "" : value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.startsWith(upperPrefix));
    }

    private ParserIssue map(ExtractionWarning warning) {
        return new ParserIssue(
                ParserIssueSeverity.valueOf(warning.severity().name()),
                warning.code(),
                warning.fieldName(),
                warning.rowNumber(),
                warning.message(),
                warning.rawValue()
        );
    }

    private MetadataConfidence map(PersianNameQuality.Status status) {
        return MetadataConfidence.valueOf(status.name());
    }

    private DataTypeConfidence map(LegacyDataTypeNormalizer.TypeStatus status) {
        return DataTypeConfidence.valueOf(status.name());
    }

    private WordDocumentFormat map(ExtractionModels.WordFormat format) {
        return WordDocumentFormat.valueOf(format.name());
    }
}
