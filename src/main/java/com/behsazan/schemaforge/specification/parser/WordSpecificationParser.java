package com.behsazan.schemaforge.specification.parser;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.specification.normalization.CheckConstraintNormalizer;
import com.behsazan.schemaforge.specification.normalization.NumericRangeParser;
import com.behsazan.schemaforge.specification.validation.IdentifierValidator;
import com.behsazan.schemaforge.specification.recovery.DataTypeNormalizer;
import com.behsazan.schemaforge.specification.recovery.RecoveryResult;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the established SchemaForge table-design DOCX format into the canonical model. */
public final class WordSpecificationParser implements SpecificationParser {
    private final IdentifierValidator identifierValidator = new IdentifierValidator();
    private final NumericRangeParser numericRangeParser = new NumericRangeParser();
    private final CheckConstraintNormalizer checkConstraintNormalizer = new CheckConstraintNormalizer();
    private final DataTypeNormalizer dataTypeNormalizer = new DataTypeNormalizer();


    private static final Pattern DATA_TYPE = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9_ ]*?)(?:\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?(?:\\s+(?:CHAR|BYTE))?\\s*\\))?$");
    private static final Pattern GROUP_REFERENCE = Pattern.compile("(?i)^([A-Z][A-Z0-9_$.]*)(?:/([YN]))?$" );
    private static final Pattern GROUP_POSITION = Pattern.compile("(?i)^([A-Z]+\\d+)(?:\\s*[,;:]\\s*(\\d+))?$" );

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public DatabaseSchema parse(SpecificationSource source) {
        List<String> recoveryWarnings = new ArrayList<>();

        Objects.requireNonNull(source, "source must not be null");
        try (XWPFDocument document = new XWPFDocument(source.content())) {
            Metadata metadata = readMetadata(document);
            List<ParsedColumn> parsedColumns = readColumns(document, recoveryWarnings);
            if (parsedColumns.isEmpty()) {
                throw new IllegalArgumentException("No column definitions were found in " + source.fileName());
            }

            String schemaName = recoverIdentifier(
                    metadata.schema(), "schema", source.fileName(), recoveryWarnings);
            String tableName = recoverIdentifier(
                    metadata.tableName(), "table", source.fileName(), recoveryWarnings);
            Table table = buildTable(
                    schemaName, tableName, metadata.description(), parsedColumns, recoveryWarnings);

            DatabaseSchema.Builder schema = DatabaseSchema.builder(schemaName)
                    .metadata("source.fileName", source.fileName())
                    .addTable(table);

            if (parsedColumns.stream().anyMatch(ParsedColumn::identity)) {
                String sequenceName = "SEQ_" + tableName;
                schema.addSequence(new Sequence(
                        validatedQualifiedName(schemaName, sequenceName, "sequence"),
                        1,
                        1,
                        null,
                        null,
                        false,
                        null,
                        Description.empty()));
            }
            schema.metadata("recovery.warningCount", Integer.toString(recoveryWarnings.size()));
            schema.metadata("recovery.warnings", String.join(System.lineSeparator(), recoveryWarnings));
            return schema.build();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read DOCX specification: " + source.fileName(), exception);
        }
    }

    private Table buildTable(
            String schemaName,
            String tableName,
            String description,
            List<ParsedColumn> parsedColumns,
            List<String> recoveryWarnings) {
        Table.Builder table = Table.builder(schemaName, tableName).description(description);
        String sequenceExpression = schemaName + ".SEQ_" + tableName + ".NEXTVAL";

        for (int index = 0; index < parsedColumns.size(); index++) {
            ParsedColumn parsed = parsedColumns.get(index);
            String defaultExpression = parsed.identity() ? sequenceExpression : emptyToNull(parsed.defaultValue());
            table.addColumn(new Column(
                    identifierValidator.toIdentifier(parsed.name(), "column"),
                    parsed.dataType(),
                    !parsed.required(),
                    new DefaultValue(defaultExpression),
                    new Description(parsed.description()),
                    parsed.identity(),
                    index + 1,
                    parsed.generatedExpression()));
        }

        List<String> primaryKeyColumns = parsedColumns.stream()
                .filter(ParsedColumn::primaryKey)
                .map(ParsedColumn::name)
                .toList();
        if (!primaryKeyColumns.isEmpty()) {
            table.primaryKey(new PrimaryKey(
                    identifierValidator.toIdentifier("PK_" + tableName, "primary key"),
                    identifiers(primaryKeyColumns)));
        }

        addUniqueKeys(table, tableName, parsedColumns);
        addIndexes(table, tableName, parsedColumns);
        addForeignKeys(table, tableName, parsedColumns, recoveryWarnings);
        addChecks(table, tableName, parsedColumns);
        return table.build();
    }

    private void addUniqueKeys(Table.Builder table, String tableName, List<ParsedColumn> columns) {
        Map<String, List<PositionedColumn>> groups = groupColumns(columns, ParsedColumn::uniqueToken);
        groups.forEach((group, members) -> table.addUniqueKey(new UniqueKey(
                identifierValidator.toIdentifier(normalizeObjectName("UK", tableName, group), "unique key"),
                identifiers(sortedNames(members)))));
    }

    private void addIndexes(Table.Builder table, String tableName, List<ParsedColumn> columns) {
        Map<String, List<PositionedColumn>> groups = groupColumns(columns, ParsedColumn::indexToken);
        groups.forEach((group, members) -> table.addIndex(new Index(
                identifierValidator.toIdentifier(normalizeObjectName("IX", tableName, group), "index"),
                sortedNames(members).stream()
                        .map(name -> new IndexColumn(identifierValidator.toIdentifier(name, "index column"), SortDirection.ASC))
                        .toList(),
                IndexType.NORMAL,
                Description.empty())));
    }

    private void addForeignKeys(
            Table.Builder table,
            String tableName,
            List<ParsedColumn> columns,
            List<String> recoveryWarnings) {
        for (ParsedColumn column : columns) {
            if (column.referenceTable() == null) {
                continue;
            }
            Reference reference = parseReference(column.referenceTable(), recoveryWarnings);
            table.addForeignKey(new ForeignKey(
                    identifierValidator.toIdentifier("FK_" + tableName + "_" + column.name(), "foreign key"),
                    List.of(identifierValidator.toIdentifier(column.name(), "foreign key column")),
                    validatedQualifiedName(reference.schema(), reference.table(), "referenced table"),
                    List.of(identifierValidator.toIdentifier(column.name(), "referenced column")),
                    ReferentialAction.NO_ACTION,
                    ReferentialAction.NO_ACTION));
        }
    }

    private void addChecks(Table.Builder table, String tableName, List<ParsedColumn> columns) {
        for (ParsedColumn column : columns) {
            String expression = emptyToNull(column.checkConstraint());
            if (expression != null) {
                addCheck(table, tableName, column.name(), qualifyCheckExpression(column.name(), expression));
                continue;
            }

            numericRangeParser.toCheckExpression(column.name(), column.range())
                    .ifPresent(rangeExpression -> addCheck(
                            table, tableName, column.name(), rangeExpression));
        }
    }

    private void addCheck(Table.Builder table, String tableName, String columnName, String expression) {
        table.addCheck(new CheckConstraint(
                identifierValidator.toIdentifier("CK_" + tableName + "_" + columnName, "check constraint"),
                expression));
    }

    private String qualifyCheckExpression(
            String columnName,
            String expression) {


        String normalized =
                normalizeText(expression);


        if (normalized.matches("^(>=|<=|<>|!=|=|>|<).*")) {

            return columnName + " " + normalized;
        }


        if (normalized.matches("^\\d+\\s*\\.\\.\\s*\\d+$")) {

            String[] bounds =
                    normalized.split("\\.\\.");

            return columnName
                    + " BETWEEN "
                    + bounds[0].trim()
                    + " AND "
                    + bounds[1].trim();
        }


        return checkConstraintNormalizer.normalize(
                columnName,
                normalized
        );
    }

    private Map<String, List<PositionedColumn>> groupColumns(
            List<ParsedColumn> columns,
            java.util.function.Function<ParsedColumn, String> tokenExtractor) {
        Map<String, List<PositionedColumn>> groups = new LinkedHashMap<>();
        for (ParsedColumn column : columns) {
            String token = emptyToNull(tokenExtractor.apply(column));
            if (token == null) {
                continue;
            }
            Matcher matcher = GROUP_POSITION.matcher(normalizeText(token).replace(" ", ""));
            String group = matcher.matches() ? matcher.group(1) : token.replaceAll("[^A-Za-z0-9_$#]", "_");
            int position = matcher.matches() && matcher.group(2) != null
                    ? Integer.parseInt(matcher.group(2))
                    : Integer.MAX_VALUE;
            groups.computeIfAbsent(group.toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(new PositionedColumn(column.name(), position));
        }
        return groups;
    }

    private List<String> sortedNames(List<PositionedColumn> columns) {
        return columns.stream()
                .sorted(Comparator.comparingInt(PositionedColumn::position))
                .map(PositionedColumn::name)
                .toList();
    }

    private String normalizeObjectName(String prefix, String tableName, String group) {
        String normalized = group.toUpperCase(Locale.ROOT);
        if (normalized.startsWith(prefix)) {
            return normalized;
        }
        return prefix + "_" + tableName + "_" + normalized;
    }

    private Reference parseReference(String rawReference, List<String> recoveryWarnings) {
        String normalized = normalizeText(rawReference).replace(" ", "").toUpperCase(Locale.ROOT);
        Matcher matcher = GROUP_REFERENCE.matcher(normalized);
        String object = matcher.matches() ? matcher.group(1) : normalized.split("/")[0];

        String[] parts = object.split("\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Invalid qualified reference: " + rawReference);
        }

        if (parts.length == 1) {
            String table = recoverIdentifier(parts[0], "referenced table", null, recoveryWarnings);
            return new Reference(null, table);
        }

        String schema = recoverIdentifier(parts[0], "referenced schema", null, recoveryWarnings);
        String table = recoverIdentifier(parts[1], "referenced table", null, recoveryWarnings);
        return new Reference(schema, table);
    }

    private Metadata readMetadata(XWPFDocument document) {
        for (XWPFTable table : document.getTables()) {
            if (table.getNumberOfRows() < 2) {
                continue;
            }
            Map<Header, Integer> headers = mapHeaders(table.getRow(0));
            if (!headers.containsKey(Header.TABLE_NAME) || !headers.containsKey(Header.SCHEMA)) {
                continue;
            }
            XWPFTableRow values = table.getRow(1);
            return new Metadata(
                    cell(values, headers.get(Header.TABLE_NAME)),
                    cell(values, headers.get(Header.SCHEMA)),
                    cell(values, headers.get(Header.TABLE_DESCRIPTION)));
        }
        throw new IllegalArgumentException("Table metadata section was not found in the DOCX specification");
    }

    private List<ParsedColumn> readColumns(XWPFDocument document, List<String> recoveryWarnings) {
        XWPFTable table = document.getTables().stream()
                .filter(candidate -> {
                    if (candidate.getNumberOfRows() == 0) {
                        return false;
                    }
                    Map<Header, Integer> headers = mapHeaders(candidate.getRow(0));
                    return headers.containsKey(Header.COLUMN_NAME) && headers.containsKey(Header.DATA_TYPE);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Column specification table was not found"));

        Map<Header, Integer> headers = mapHeaders(table.getRow(0));
        List<ParsedColumn> result = new ArrayList<>();
        Map<String, Integer> firstDefinitionRows = new LinkedHashMap<>();
        for (int rowIndex = 1; rowIndex < table.getNumberOfRows(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            String rawName = cell(row, headers.get(Header.COLUMN_NAME));
            if (rawName == null) {
                continue;
            }
            String name = recoverIdentifier(rawName, "column", null, recoveryWarnings);
            String rawType = cell(row, headers.get(Header.DATA_TYPE));
            if (rawType == null || rawType.isBlank()) {
                continue;
            }
            String key = cell(row, headers.get(Header.KEY));
            ParsedColumn parsedColumn = new ParsedColumn(
                    name,
                    cell(row, headers.get(Header.COLUMN_DESCRIPTION)),
                    parseDataType(rawType, recoveryWarnings),
                    normalizeText(rawType).toUpperCase(Locale.ROOT).contains("IDENTITY"),
                    containsToken(key, "PK"),
                    extractReference(key),
                    isMarked(cell(row, headers.get(Header.REQUIRED))),
                    cell(row, headers.get(Header.DEFAULT_VALUE)),
                    cell(row, headers.get(Header.UNIQUE)),
                    cell(row, headers.get(Header.INDEX)),
                    cell(row, headers.get(Header.RANGE)),
                    cell(row, headers.get(Header.CHECK_CONSTRAINT)),
                    cell(row, headers.get(Header.GENERATED_EXPRESSION)));

            String duplicateKey = name.toUpperCase(Locale.ROOT);
            Integer firstRow = firstDefinitionRows.putIfAbsent(duplicateKey, rowIndex + 1);
            if (firstRow != null) {
                recoveryWarnings.add(duplicateColumnWarning(
                        name,
                        firstRow,
                        rowIndex + 1,
                        rawType,
                        parsedColumn));
                continue;
            }
            result.add(parsedColumn);
        }
        return result;
    }

    private String duplicateColumnWarning(
            String name,
            int firstRow,
            int duplicateRow,
            String rawType,
            ParsedColumn column) {
        StringBuilder definition = new StringBuilder(name).append(" ").append(normalizeText(rawType));
        if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
            definition.append(" DEFAULT ").append(normalizeText(column.defaultValue()));
        }
        if (column.required()) {
            definition.append(" NOT NULL");
        }
        return "DUPLICATE_COLUMN"
                + "|name=" + warningValue(name)
                + "|firstRow=" + firstRow
                + "|duplicateRow=" + duplicateRow
                + "|definition=" + warningValue(definition.toString());
    }

    private String warningValue(String value) {
        return normalizeText(value).replace("|", "/");
    }

    private DataType parseDataType(String rawValue, List<String> recoveryWarnings) {
        RecoveryResult recovery = dataTypeNormalizer.normalize(rawValue);
        recoveryWarnings.addAll(recovery.warnings());

        String normalized = normalizeText(recovery.value())
                .toUpperCase(Locale.ROOT)
                .replace("IDENTITY", "")
                .replaceAll("\\s+", " ")
                .replaceFirst("^TIMESTAMP\\s*\\(([^)]+)\\)\\s+WITH LOCAL TIME ZONE$",
                        "TIMESTAMP WITH LOCAL TIME ZONE($1)")
                .replaceFirst("^TIMESTAMP\\s*\\(([^)]+)\\)\\s+WITH TIME ZONE$",
                        "TIMESTAMP WITH TIME ZONE($1)")
                .trim();
        Matcher matcher = DATA_TYPE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported data type: " + rawValue);
        }

        String name = normalizeDataTypeName(matcher.group(1));
        Integer first = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        Integer second = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        validateDataTypeParameters(name, first, second, rawValue);

        if (first == null) {
            return DataType.simple(name);
        }
        if (isLengthType(name)) {
            return DataType.varchar(name, first);
        }
        return DataType.numeric(name, first, second);
    }

    private String normalizeDataTypeName(String rawName) {
        String name = rawName.trim().replaceAll("\\s+", " ");
        return switch (name) {
            case "INT" -> "INTEGER";
            case "DECIMAL", "NUMERIC" -> "NUMBER";
            case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP_WITH_TIME_ZONE";
            case "TIMESTAMP WITH LOCAL TIME ZONE" -> "TIMESTAMP_WITH_LOCAL_TIME_ZONE";
            case "LONG RAW" -> "LONG_RAW";
            default -> name.replace(" ", "");
        };
    }

    private void validateDataTypeParameters(String name, Integer first, Integer second, String rawValue) {
        boolean supported = switch (name) {
            case "NUMBER", "INTEGER", "SMALLINT", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE",
                 "VARCHAR2", "VARCHAR", "NVARCHAR2", "CHAR", "NCHAR", "RAW",
                 "DATE", "TIMESTAMP", "TIMESTAMP_WITH_TIME_ZONE",
                 "TIMESTAMP_WITH_LOCAL_TIME_ZONE", "CLOB", "NCLOB", "BLOB",
                 "LONG_RAW", "XMLTYPE", "JSON" -> true;
            default -> false;
        };
        if (!supported) {
            throw new IllegalArgumentException("Unsupported data type: " + rawValue);
        }
        if (second != null && !name.equals("NUMBER")) {
            throw new IllegalArgumentException("Scale is only supported for NUMBER: " + rawValue);
        }
        if (first != null && !(isLengthType(name) || name.equals("NUMBER") || name.startsWith("TIMESTAMP"))) {
            throw new IllegalArgumentException("Data type does not accept parameters: " + rawValue);
        }
    }

    private boolean isLengthType(String name) {
        return name.equals("VARCHAR2") || name.equals("VARCHAR") || name.equals("CHAR")
                || name.equals("NVARCHAR2") || name.equals("NCHAR") || name.equals("RAW");
    }

    private Map<Header, Integer> mapHeaders(XWPFTableRow row) {
        Map<Header, Integer> result = new EnumMap<>(Header.class);
        if (row == null) {
            return result;
        }
        for (int index = 0; index < row.getTableCells().size(); index++) {
            Header header = Header.from(cell(row, index));
            if (header != Header.UNKNOWN) {
                result.putIfAbsent(header, index);
            }
        }
        return result;
    }

    private String extractReference(String keyValue) {
        String value = emptyToNull(keyValue);
        if (value == null || containsToken(value, "PK") || !value.contains("/")) {
            return null;
        }
        return value;
    }

    private boolean containsToken(String value, String token) {
        return value != null && normalizeText(value).toUpperCase(Locale.ROOT).contains(token);
    }

    private boolean isMarked(String value) {
        if (value == null) {
            return false;
        }
        String normalized = normalizeText(value).toUpperCase(Locale.ROOT);
        return normalized.contains("√") || normalized.equals("Y") || normalized.equals("YES")
                || normalized.equals("TRUE") || normalized.equals("1");
    }

    private String cell(XWPFTableRow row, Integer index) {
        if (row == null || index == null || index < 0 || index >= row.getTableCells().size()) {
            return null;
        }
        XWPFTableCell cell = row.getCell(index);
        return cell == null ? null : emptyToNull(cell.getText());
    }



    private String recoverIdentifier(
            String rawIdentifier,
            String objectType,
            String sourceName,
            List<String> recoveryWarnings) {
        String original = emptyToNull(rawIdentifier);
        if (original == null) {
            String suffix = sourceName == null ? "" : " in " + sourceName;
            throw new IllegalArgumentException("Missing " + objectType + " identifier" + suffix);
        }

        if (original.contains(".")) {
            throw new IllegalArgumentException(
                    "Qualified name is not valid for a single " + objectType + ": " + original);
        }

        String normalized = normalizeText(original).toUpperCase(Locale.ROOT);
        String sanitized = normalized
                .replace(' ', '_')
                .replace('-', '_')
                .replace('/', '_')
                .replaceAll("[^A-Z0-9_$#]", "")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        if (sanitized.isBlank()) {
            throw new IllegalArgumentException(
                    "Unable to recover " + objectType + " identifier: " + original);
        }

        if (!sanitized.equals(normalized)) {
            recoveryWarnings.add(
                    "Recovered " + objectType + " identifier '" + original + "' as '" + sanitized + "'");
        }

        return sourceName == null
                ? identifierValidator.requireValid(sanitized, objectType)
                : identifierValidator.requireValid(sanitized, objectType, sourceName);
    }

    private QualifiedName validatedQualifiedName(String schemaName, String objectName, String objectType) {
        String validatedObjectName = identifierValidator.requireValid(objectName, objectType);
        if (schemaName == null || schemaName.isBlank()) {
            return QualifiedName.of(null, validatedObjectName);
        }
        return QualifiedName.of(
                identifierValidator.requireValid(schemaName, "schema"),
                validatedObjectName);
    }

    private List<Identifier> identifiers(List<String> names) {
        return names.stream().map(name -> identifierValidator.toIdentifier(name, "column")).toList();
    }

    private String firstNonBlank(String first, String second) {
        String value = emptyToNull(first);
        return value != null ? value : emptyToNull(second);
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }

        value = value
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();

        return value.isEmpty() ? null : value;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private enum Header {
        TABLE_NAME,
        SCHEMA,
        TABLE_DESCRIPTION,
        COLUMN_NAME,
        COLUMN_DESCRIPTION,
        DATA_TYPE,
        KEY,
        UNIQUE,
        INDEX,
        REQUIRED,
        DEFAULT_VALUE,
        RANGE,
        CHECK_CONSTRAINT,
        GENERATED_EXPRESSION,
        UNKNOWN;

        private static Header from(String rawValue) {
            String value = rawValue == null ? "" : rawValue.replace('\n', ' ').replace('\r', ' ')
                    .trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (value.contains("TABLE NAME") || value.contains("نام جدول")) return TABLE_NAME;
            if (value.equals("SCHEMA") || value.contains("SCHEMA ")
                    || value.contains("نام طرحواره") || value.equals("طرحواره")) return SCHEMA;
            if (value.contains("هدف از طراحی جدول") || value.contains("شرح جدول")
                    || value.contains("TABLE PURPOSE") || value.contains("TABLE DESCRIPTION")) return TABLE_DESCRIPTION;
            if (value.contains("COLUMN NAME") || value.contains("نام ستون")) return COLUMN_NAME;
            if (value.contains("نام فارسی ستون") || value.contains("شرح ستون")
                    || value.contains("COLUMN DESCRIPTION") || value.contains("PERSIAN COLUMN")) return COLUMN_DESCRIPTION;
            if (value.contains("DATA TYPE") || value.contains("DATATYPE") || value.contains("نوع داده")) return DATA_TYPE;
            if (value.contains("PRIMARY") || value.contains("FOREIGN") || value.contains("کلید")) return KEY;
            if (value.contains("UNIQUE") || value.contains("یکتا")) return UNIQUE;
            if (value.equals("INDEX") || value.contains("INDEX NAME")
                    || value.contains("ایندکس") || value.contains("شاخص")) return INDEX;
            if (value.contains("REQUIRED") || value.contains("MANDATORY") || value.contains("اجباری")) return REQUIRED;
            if (value.contains("DEFAULT") || value.contains("مقدار پیش فرض") || value.contains("پیش فرض")) return DEFAULT_VALUE;
            if (value.equals("RANGE") || value.contains("دامنه") || value.contains("محدوده")) return RANGE;
            if (value.contains("CHECK") || value.contains("CHECK CONSTRAINT")
                    || value.contains("محدودیت کنترلی")) return CHECK_CONSTRAINT;
            if (value.contains("VIRTUAL COLUMN") || value.contains("VIRTUAL EXPRESSION")
                    || value.contains("GENERATED EXPRESSION") || value.contains("COLUMN EXPRESSION")
                    || value.contains("عبارت ستون مجازی") || value.contains("ستون مجازی")) {
                return GENERATED_EXPRESSION;
            }
            return UNKNOWN;
        }
    }

    private record Metadata(String tableName, String schema, String description) {
    }

    private record ParsedColumn(
            String name,
            String description,
            DataType dataType,
            boolean identity,
            boolean primaryKey,
            String referenceTable,
            boolean required,
            String defaultValue,
            String uniqueToken,
            String indexToken,
            String range,
            String checkConstraint,
            String generatedExpression) {
    }

    private record PositionedColumn(String name, int position) {
    }

    private record Reference(String schema, String table) {
    }
}
