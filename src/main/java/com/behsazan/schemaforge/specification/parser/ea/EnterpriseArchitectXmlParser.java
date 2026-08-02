package com.behsazan.schemaforge.specification.parser.ea;

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
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Architect XMI 1.x importer.
 *
 * <p>The importer is an input adapter only: it converts EA table/column/key/index
 * elements to the existing DBMS-neutral SchemaForge model. DDL and comparison
 * workbooks continue to use the normal Oracle/PostgreSQL pipeline.</p>
 */
public final class EnterpriseArchitectXmlParser {
    private static final Pattern FK_INFO = Pattern.compile(
            "(?i)FKINFO\\s*=\\s*SRC=([^:;]+):DST=([^:;]+):");
    private static final Pattern COLUMN_PAIR = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_$#]*)\\s*=\\s*([A-Za-z][A-Za-z0-9_$#]*)");
    private static final Pattern CHECK_WRAPPER = Pattern.compile(
            "(?is)^CHECK\\s*\\((.*)\\)\\s*;?$");
    private static final String DEFAULT_SCHEMA = "COL";
    private static final Set<String> SCHEMA_TAGS = Set.of(
            "SCHEMA", "SCHEMA_NAME", "SCHEMANAME", "OWNER", "DATABASE_OWNER",
            "DATABASE_SCHEMA", "DB_SCHEMA", "DBSCHEMA");

    private final String configuredDefaultSchema;
    private final boolean primaryKeyAsIdentity;

    public EnterpriseArchitectXmlParser() {
        this(DEFAULT_SCHEMA, false);
    }

    public EnterpriseArchitectXmlParser(String configuredDefaultSchema) {
        this(configuredDefaultSchema, false);
    }

    public EnterpriseArchitectXmlParser(String configuredDefaultSchema, boolean primaryKeyAsIdentity) {
        this.configuredDefaultSchema = sanitizeIdentifier(
                firstNonBlank(configuredDefaultSchema, DEFAULT_SCHEMA), DEFAULT_SCHEMA);
        this.primaryKeyAsIdentity = primaryKeyAsIdentity;
    }

    public DatabaseSchema parse(String fileName, InputStream inputStream) {
        return parse(fileName, inputStream, null);
    }

    public DatabaseSchema parse(String fileName, InputStream inputStream, String schemaOverride) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        try {
            Document document = secureFactory().newDocumentBuilder().parse(inputStream);
            document.getDocumentElement().normalize();

            List<Element> tableElements = findTableClasses(document);
            if (tableElements.isEmpty()) {
                throw new IllegalArgumentException(
                        "No Enterprise Architect table elements were found in XML/XMI: " + fileName);
            }

            String requestedSchema = schemaOverride == null || schemaOverride.isBlank()
                    ? ""
                    : sanitizeIdentifier(schemaOverride, configuredDefaultSchema);
            String globalXmlSchema = findExplicitSchema(document.getDocumentElement());
            if (globalXmlSchema.isBlank()) {
                globalXmlSchema = tableElements.stream()
                        .map(EnterpriseArchitectXmlParser::findExplicitSchema)
                        .filter(value -> !value.isBlank())
                        .findFirst().orElse("");
            }
            String schemaName = sanitizeIdentifier(
                    firstNonBlank(requestedSchema, globalXmlSchema, configuredDefaultSchema),
                    configuredDefaultSchema);
            boolean forceRequestedSchema = !requestedSchema.isBlank();

            Map<String, EaTable> tablesById = new LinkedHashMap<>();
            for (Element tableElement : tableElements) {
                EaTable table = parseTableDefinition(tableElement, schemaName, forceRequestedSchema);
                tablesById.put(table.xmiId(), table);
            }

            List<EaAssociation> associations = parseAssociations(document);
            Map<String, EaAssociation> associationBySourceOperation = new HashMap<>();
            for (EaAssociation association : associations) {
                if (!association.sourceOperation().isBlank()) {
                    associationBySourceOperation.put(
                            operationKey(association.sourceTableId(), association.sourceOperation()), association);
                }
            }

            List<String> warnings = new ArrayList<>();
            DatabaseSchema.Builder schema = DatabaseSchema.builder(schemaName)
                    .metadata("source.fileName", fileName)
                    .metadata("source.format", "EA-XMI")
                    .metadata("source.eaExporter", exporter(document))
                    .metadata("source.eaExporterVersion", exporterVersion(document))
                    .metadata("source.eaDefaultSchema", configuredDefaultSchema)
                    .metadata("source.eaRequestedSchema", requestedSchema)
                    .metadata("source.eaSchemaResolution", forceRequestedSchema
                            ? "API_PARAMETER"
                            : (globalXmlSchema.isBlank() ? "CONFIG_DEFAULT" : "XML"));

            for (EaTable eaTable : tablesById.values()) {
                schema.addTable(buildTable(
                        eaTable, tablesById, associationBySourceOperation, warnings,
                        primaryKeyAsIdentity));
            }
            if (!warnings.isEmpty()) {
                schema.metadata("recovery.warningCount", Integer.toString(warnings.size()));
                schema.metadata("recovery.warnings", String.join(System.lineSeparator(), warnings));
            }
            schema.metadata("source.eaTableCount", Integer.toString(tablesById.size()));
            return schema.build();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse EA XML/XMI: " + fileName, exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private EaTable parseTableDefinition(
            Element tableElement, String defaultSchema, boolean forceDefaultSchema) {
        Map<String, String> tableTags = taggedValues(tableElement);
        String tableName = sanitizeIdentifier(attribute(tableElement, "name"), "UNNAMED_TABLE");
        String tableSchema = forceDefaultSchema
                ? defaultSchema
                : sanitizeIdentifier(
                        firstNonBlank(schemaFromTags(tableTags),
                                findExplicitSchema(parentElement(tableElement)), defaultSchema),
                        defaultSchema);
        String description = normalizeDocumentation(firstNonBlank(
                tag(tableTags, "documentation"),
                tag(tableTags, "notes"),
                tag(tableTags, "description"),
                tag(tableTags, "alias")));

        List<EaColumn> columns = new ArrayList<>();
        int fallbackPosition = 0;
        for (Element attribute : directDescendants(tableElement, "Attribute")) {
            if (!isStereotype(attribute, "column")) continue;
            columns.add(parseColumn(attribute, fallbackPosition++));
        }
        columns.sort(Comparator.comparingInt(EaColumn::position));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("EA table " + tableName + " does not contain any column elements");
        }

        List<EaOperation> operations = new ArrayList<>();
        for (Element operation : directDescendants(tableElement, "Operation")) {
            operations.add(parseOperation(operation));
        }

        return new EaTable(
                firstNonBlank(xmiId(tableElement), tableName),
                tableSchema,
                tableName,
                description,
                List.copyOf(columns),
                List.copyOf(operations));
    }

    private static EaColumn parseColumn(Element attribute, int fallbackPosition) {
        Map<String, String> tags = taggedValues(attribute);
        String name = sanitizeIdentifier(attribute(attribute, "name"), "COLUMN_" + (fallbackPosition + 1));
        String rawType = firstNonBlank(tag(tags, "type"), attribute(attribute, "type"), "VARCHAR2");
        int position = nonNegativeInt(tag(tags, "position"), fallbackPosition);
        int length = nonNegativeInt(tag(tags, "length"), 0);
        int precision = nonNegativeInt(tag(tags, "precision"), 0);
        Integer scale = nullableNonNegativeInt(tag(tags, "scale"));
        String lower = firstNonBlank(tag(tags, "lowerBound"), attribute(attribute, "lower"));
        boolean nullable = lower.isBlank() || nonNegativeInt(lower, 0) == 0;
        String description = normalizeDocumentation(firstNonBlank(
                tag(tags, "documentation"),
                tag(tags, "notes"),
                tag(tags, "description"),
                tag(tags, "style"),
                styleExValue(tag(tags, "styleex"), "alias")));
        String defaultExpression = initialValue(attribute, tags);
        boolean generated = truthy(tag(tags, "derived")) && !defaultExpression.isBlank();
        boolean identity = truthy(firstNonBlank(
                tag(tags, "identity"), tag(tags, "autonum"), tag(tags, "autoIncrement"),
                tag(tags, "dbAutoNum")));

        DataType dataType = mapDataType(rawType, length, precision, scale, tags);
        return new EaColumn(
                name,
                dataType,
                nullable,
                generated ? null : emptyToNull(defaultExpression),
                description,
                identity,
                position,
                generated ? defaultExpression : null);
    }

    private static EaOperation parseOperation(Element operation) {
        Map<String, String> tags = taggedValues(operation);
        String name = sanitizeIdentifier(attribute(operation, "name"), "UNNAMED_OPERATION");
        String stereotype = normalizedStereotype(operation);
        List<EaParameter> parameters = new ArrayList<>();
        int fallback = 0;
        for (Element parameter : directDescendants(operation, "Parameter")) {
            String kind = firstNonBlank(attribute(parameter, "kind"), "in");
            String parameterName = attribute(parameter, "name");
            if (!"in".equalsIgnoreCase(kind) || parameterName.isBlank()) continue;
            Map<String, String> parameterTags = taggedValues(parameter);
            int position = nonNegativeInt(tag(parameterTags, "pos"), fallback++);
            SortDirection direction = parseSortDirection(parameterTags);
            parameters.add(new EaParameter(
                    sanitizeIdentifier(parameterName, "COLUMN_" + (position + 1)), position, direction));
        }
        parameters.sort(Comparator.comparingInt(EaParameter::position));
        return new EaOperation(name, stereotype, List.copyOf(parameters), tags);
    }

    private static List<EaAssociation> parseAssociations(Document document) {
        List<EaAssociation> result = new ArrayList<>();
        for (Element association : elements(document, "Association")) {
            if (!isStereotype(association, "FK")) continue;
            Map<String, String> tags = taggedValues(association);
            String sourceOperation = "";
            String targetOperation = "";
            Matcher matcher = FK_INFO.matcher(tag(tags, "styleex"));
            if (matcher.find()) {
                sourceOperation = sanitizeIdentifier(matcher.group(1), "");
                targetOperation = sanitizeIdentifier(matcher.group(2), "");
            }

            String sourceId = "";
            String targetId = "";
            for (Element end : directDescendants(association, "AssociationEnd")) {
                Map<String, String> endTags = taggedValues(end);
                String side = tag(endTags, "ea_end");
                if ("source".equalsIgnoreCase(side)) {
                    sourceId = attribute(end, "type");
                    if (sourceOperation.isBlank()) sourceOperation = sanitizeIdentifier(attribute(end, "name"), "");
                } else if ("target".equalsIgnoreCase(side)) {
                    targetId = attribute(end, "type");
                    if (targetOperation.isBlank()) targetOperation = sanitizeIdentifier(attribute(end, "name"), "");
                }
            }

            List<ColumnPair> pairs = new ArrayList<>();
            Matcher pairMatcher = COLUMN_PAIR.matcher(firstNonBlank(
                    attribute(association, "name"), tag(tags, "mt")));
            while (pairMatcher.find()) {
                pairs.add(new ColumnPair(
                        sanitizeIdentifier(pairMatcher.group(1), ""),
                        sanitizeIdentifier(pairMatcher.group(2), "")));
            }
            result.add(new EaAssociation(
                    sourceId,
                    targetId,
                    sourceOperation,
                    targetOperation,
                    firstNonBlank(tag(tags, "ea_sourceName"), ""),
                    firstNonBlank(tag(tags, "ea_targetName"), ""),
                    List.copyOf(pairs),
                    tags));
        }
        return List.copyOf(result);
    }

    private static Table buildTable(
            EaTable eaTable,
            Map<String, EaTable> tablesById,
            Map<String, EaAssociation> associationBySourceOperation,
            List<String> warnings,
            boolean primaryKeyAsIdentity) {

        EaOperation primaryKey = eaTable.operations().stream()
                .filter(operation -> operationKind(operation) == OperationKind.PRIMARY_KEY)
                .findFirst().orElse(null);
        Set<String> primaryKeyColumns = primaryKey == null
                ? Set.of()
                : primaryKey.parameters().stream()
                        .map(EaParameter::name)
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Table.Builder builder = Table.builder(eaTable.schema(), eaTable.name())
                .description(eaTable.description());
        for (EaColumn column : eaTable.columns()) {
            boolean inferredIdentity = primaryKeyAsIdentity
                    && primaryKeyColumns.contains(column.name().toUpperCase(Locale.ROOT));
            builder.addColumn(new Column(
                    Identifier.of(column.name()),
                    column.dataType(),
                    inferredIdentity ? false : column.nullable(),
                    new DefaultValue(inferredIdentity ? null : column.defaultValue()),
                    new Description(column.description()),
                    column.identity() || inferredIdentity,
                    column.position() + 1,
                    inferredIdentity ? null : column.generatedExpression()));
        }

        if (primaryKey != null && !primaryKey.parameters().isEmpty()) {
            builder.primaryKey(new PrimaryKey(
                    Identifier.of(primaryKey.name()), identifiers(primaryKey.parameters()),
                    truthy(tag(primaryKey.tags(), "deferrable")),
                    truthy(tag(primaryKey.tags(), "initiallyDeferred"))));
        }

        for (EaOperation operation : eaTable.operations()) {
            OperationKind kind = operationKind(operation);
            switch (kind) {
                case UNIQUE_KEY -> addUniqueKey(builder, operation);
                case INDEX, UNIQUE_INDEX -> addIndex(builder, operation, kind == OperationKind.UNIQUE_INDEX);
                case CHECK -> addCheck(builder, operation);
                case FOREIGN_KEY -> addForeignKey(
                        builder, eaTable, operation, tablesById,
                        associationBySourceOperation, warnings);
                default -> { }
            }
        }
        return builder.build();
    }


    private static void addCheck(Table.Builder builder, EaOperation operation) {
        String expression = normalizeCheckExpression(firstNonBlank(
                tag(operation.tags(), "expression"),
                tag(operation.tags(), "condition"),
                tag(operation.tags(), "check"),
                tag(operation.tags(), "definition"),
                tag(operation.tags(), "body"),
                tag(operation.tags(), "code")));
        if (expression.isBlank()) return;
        builder.addCheck(new CheckConstraint(Identifier.of(operation.name()), expression));
    }

    private static void addUniqueKey(Table.Builder builder, EaOperation operation) {
        if (operation.parameters().isEmpty()) return;
        builder.addUniqueKey(new UniqueKey(
                Identifier.of(operation.name()),
                identifiers(operation.parameters()),
                truthy(tag(operation.tags(), "deferrable")),
                truthy(tag(operation.tags(), "initiallyDeferred"))));
    }

    private static void addIndex(Table.Builder builder, EaOperation operation, boolean forceUnique) {
        if (operation.parameters().isEmpty()) return;
        List<IndexColumn> columns = operation.parameters().stream()
                .map(parameter -> new IndexColumn(Identifier.of(parameter.name()), parameter.direction()))
                .toList();
        builder.addIndex(new Index(
                Identifier.of(operation.name()),
                columns,
                indexType(operation, forceUnique),
                Description.empty()));
    }

    private static void addForeignKey(
            Table.Builder builder,
            EaTable eaTable,
            EaOperation operation,
            Map<String, EaTable> tablesById,
            Map<String, EaAssociation> associationBySourceOperation,
            List<String> warnings) {

        EaAssociation association = associationBySourceOperation.get(
                operationKey(eaTable.xmiId(), operation.name()));
        if (association == null) {
            warnings.add("EA_FK_ASSOCIATION_NOT_FOUND|table=" + eaTable.name()
                    + "|foreignKey=" + operation.name());
            return;
        }

        EaTable target = tablesById.get(association.targetTableId());
        String targetName = target == null
                ? sanitizeIdentifier(association.targetTableName(), "UNRESOLVED_TABLE")
                : target.name();
        String targetSchema = target == null ? eaTable.schema() : target.schema();

        List<String> localColumns = operation.parameters().stream().map(EaParameter::name).toList();
        if (localColumns.isEmpty()) {
            localColumns = association.columnPairs().stream().map(ColumnPair::source).toList();
        }

        List<String> referencedColumns = List.of();
        if (target != null) {
            EaOperation targetOperation = target.operations().stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(association.targetOperation()))
                    .findFirst().orElse(null);
            if (targetOperation != null) {
                referencedColumns = targetOperation.parameters().stream().map(EaParameter::name).toList();
            }
        }
        if (referencedColumns.isEmpty()) {
            referencedColumns = association.columnPairs().stream().map(ColumnPair::target).toList();
        }

        if (localColumns.isEmpty() || referencedColumns.isEmpty()
                || localColumns.size() != referencedColumns.size()) {
            warnings.add("EA_FK_COLUMNS_UNRESOLVED|table=" + eaTable.name()
                    + "|foreignKey=" + operation.name());
            return;
        }

        builder.addForeignKey(new ForeignKey(
                Identifier.of(operation.name()),
                localColumns.stream().map(Identifier::of).toList(),
                QualifiedName.of(targetSchema, targetName),
                referencedColumns.stream().map(Identifier::of).toList(),
                referentialAction(firstNonBlank(
                        tag(operation.tags(), "Delete"), tag(operation.tags(), "onDelete"),
                        tag(association.tags(), "Delete"), propertyAction(operation.tags(), "Delete"))),
                referentialAction(firstNonBlank(
                        tag(operation.tags(), "Update"), tag(operation.tags(), "onUpdate"),
                        tag(association.tags(), "Update"), propertyAction(operation.tags(), "Update"))),
                truthy(tag(operation.tags(), "deferrable")),
                truthy(tag(operation.tags(), "initiallyDeferred")),
                true,
                false));
    }

    private static OperationKind operationKind(EaOperation operation) {
        String stereotype = operation.stereotype().replaceAll("[^A-Z0-9]", "");
        return switch (stereotype) {
            case "PK", "PRIMARYKEY" -> OperationKind.PRIMARY_KEY;
            case "FK", "FOREIGNKEY" -> OperationKind.FOREIGN_KEY;
            case "UK", "UNIQUE", "UNIQUEKEY", "UNIQUECONSTRAINT" -> OperationKind.UNIQUE_KEY;
            case "UNIQUEINDEX", "UI", "UX" -> OperationKind.UNIQUE_INDEX;
            case "INDEX", "IDX" -> uniqueByNameOrTag(operation)
                    ? OperationKind.UNIQUE_INDEX : OperationKind.INDEX;
            case "CHECK", "CK", "CHECKCONSTRAINT" -> OperationKind.CHECK;
            default -> OperationKind.OTHER;
        };
    }

    private static boolean uniqueByNameOrTag(EaOperation operation) {
        String name = operation.name().toUpperCase(Locale.ROOT);
        return name.startsWith("UX_") || name.startsWith("UI_")
                || truthy(tag(operation.tags(), "unique"))
                || "UNIQUE".equalsIgnoreCase(tag(operation.tags(), "indexType"));
    }

    private static IndexType indexType(EaOperation operation, boolean forceUnique) {
        if (forceUnique || uniqueByNameOrTag(operation)) return IndexType.UNIQUE;
        String value = firstNonBlank(
                tag(operation.tags(), "indexType"), tag(operation.tags(), "type"), operation.name())
                .toUpperCase(Locale.ROOT);
        if (value.contains("BITMAP")) return IndexType.BITMAP;
        if (value.contains("FUNCTION")) return IndexType.FUNCTION_BASED;
        if (value.contains("NONCLUSTERED")) return IndexType.NONCLUSTERED;
        if (value.contains("CLUSTERED")) return IndexType.CLUSTERED;
        return IndexType.NORMAL;
    }

    private static DataType mapDataType(
            String rawType,
            int length,
            int precision,
            Integer scale,
            Map<String, String> tags) {
        String normalized = rawType.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        String canonicalName = switch (normalized) {
            case "VARCHAR", "VARCHAR2" -> "VARCHAR2";
            case "NVARCHAR", "NVARCHAR2" -> "NVARCHAR2";
            case "DECIMAL", "NUMERIC", "NUMBER" -> "NUMBER";
            case "INTEGER", "INT", "SMALLINT", "BIGINT" -> normalized;
            case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP_WITH_TIME_ZONE";
            case "TIMESTAMP WITH LOCAL TIME ZONE" -> "TIMESTAMP_WITH_LOCAL_TIME_ZONE";
            case "DOUBLE PRECISION" -> "DOUBLE";
            default -> sanitizeIdentifier(normalized.replace(' ', '_'), "VARCHAR2");
        };

        if (isCharacterType(canonicalName) || "RAW".equals(canonicalName)) {
            int effectiveLength = length > 0 ? length : (precision > 0 ? precision : 255);
            return new DataType(
                    Identifier.of(canonicalName),
                    effectiveLength,
                    lengthSemantics(tags),
                    null,
                    null);
        }
        if (isNumericType(canonicalName)) {
            if (precision > 0) {
                Integer effectiveScale = scale == null ? null : Math.min(scale, precision);
                return new DataType(
                        Identifier.of(canonicalName), null, LengthSemantics.DEFAULT,
                        precision, effectiveScale);
            }
            return DataType.simple(canonicalName);
        }
        return DataType.simple(canonicalName);
    }

    private static boolean isCharacterType(String name) {
        return Set.of("VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHAR", "NCHAR")
                .contains(name);
    }

    private static boolean isNumericType(String name) {
        return Set.of("NUMBER", "NUMERIC", "DECIMAL", "INTEGER", "INT", "SMALLINT", "BIGINT", "FLOAT")
                .contains(name);
    }

    private static LengthSemantics lengthSemantics(Map<String, String> tags) {
        String value = firstNonBlank(
                tag(tags, "lengthSemantics"), tag(tags, "charSemantics"), tag(tags, "lengthType"))
                .toUpperCase(Locale.ROOT);
        if (value.contains("CHAR")) return LengthSemantics.CHAR;
        if (value.contains("BYTE")) return LengthSemantics.BYTE;
        return LengthSemantics.DEFAULT;
    }

    private static SortDirection parseSortDirection(Map<String, String> tags) {
        String value = firstNonBlank(
                tag(tags, "direction"), tag(tags, "sort"), tag(tags, "order"), tag(tags, "styleex"))
                .toUpperCase(Locale.ROOT);
        return value.contains("DESC") ? SortDirection.DESC : SortDirection.ASC;
    }

    private static ReferentialAction referentialAction(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CASCADE" -> ReferentialAction.CASCADE;
            case "RESTRICT" -> ReferentialAction.RESTRICT;
            case "SET_NULL", "SETNULL" -> ReferentialAction.SET_NULL;
            case "SET_DEFAULT", "SETDEFAULT" -> ReferentialAction.SET_DEFAULT;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static String propertyAction(Map<String, String> tags, String action) {
        String property = tag(tags, "property");
        if (property.isBlank()) return "";
        Matcher matcher = Pattern.compile("(?i)" + Pattern.quote(action)
                + "\\s+([^=;]+)\\s*=\\s*1").matcher(property);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static List<Identifier> identifiers(List<EaParameter> parameters) {
        return parameters.stream().map(EaParameter::name).map(Identifier::of).toList();
    }

    private static List<Element> findTableClasses(Document document) {
        List<Element> result = new ArrayList<>();
        for (Element element : elements(document, "Class")) {
            if (isStereotype(element, "table")) result.add(element);
        }
        return List.copyOf(result);
    }

    private static List<Element> elements(Document document, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = document.getElementsByTagName(localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) result.add(element);
        }
        return result;
    }

    /** Returns descendants under the same classifier/association, excluding nested classes. */
    private static List<Element> directDescendants(Element owner, String localName) {
        List<Element> result = new ArrayList<>();
        collectDirectDescendants(owner, owner, localName, result);
        return result;
    }

    private static void collectDirectDescendants(
            Element root, Element current, String localName, List<Element> result) {
        NodeList children = current.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element child)) continue;
            if (child != root && "Class".equals(localName(child))) continue;
            if (localName.equals(localName(child))) result.add(child);
            else collectDirectDescendants(root, child, localName, result);
        }
    }

    private static List<Element> childElements(Element owner) {
        List<Element> result = new ArrayList<>();
        NodeList children = owner.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) result.add(element);
        }
        return result;
    }

    private static Map<String, String> taggedValues(Element owner) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element container : childElements(owner)) {
            if (!"ModelElement.taggedValue".equals(localName(container))) continue;
            for (Element element : childElements(container)) {
                if (!"TaggedValue".equals(localName(element))) continue;
                String key = attribute(element, "tag");
                if (!key.isBlank()) {
                    result.putIfAbsent(key.toUpperCase(Locale.ROOT), attribute(element, "value"));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String tag(Map<String, String> tags, String name) {
        return tags.getOrDefault(name.toUpperCase(Locale.ROOT), "");
    }

    private static boolean isStereotype(Element element, String expected) {
        String normalized = normalizedStereotype(element);
        return normalized.equals(expected.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
    }

    private static String normalizedStereotype(Element element) {
        for (Element container : childElements(element)) {
            if (!"ModelElement.stereotype".equals(localName(container))) continue;
            for (Element stereotype : childElements(container)) {
                if (!"Stereotype".equals(localName(stereotype))) continue;
                String value = attribute(stereotype, "name");
                if (!value.isBlank()) {
                    return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                }
            }
        }
        return tag(taggedValues(element), "stereotype")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String findExplicitSchema(Element element) {
        Element current = element;
        while (current != null) {
            String schema = schemaFromTags(taggedValues(current));
            if (!schema.isBlank()) return schema;
            current = parentElement(current);
        }
        return "";
    }

    private static String schemaFromTags(Map<String, String> tags) {
        for (String name : SCHEMA_TAGS) {
            String value = tag(tags, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Element parentElement(Node node) {
        Node parent = node == null ? null : node.getParentNode();
        return parent instanceof Element element ? element : null;
    }

    private static String initialValue(Element attribute, Map<String, String> tags) {
        for (Element expression : directDescendants(attribute, "Expression")) {
            String value = firstNonBlank(
                    attribute(expression, "body"), attribute(expression, "value"),
                    attribute(expression, "text"), expression.getTextContent());
            if (!value.isBlank()) return value.trim();
        }
        return firstNonBlank(tag(tags, "default"), tag(tags, "defaultValue"), tag(tags, "default_value"));
    }

    private static String exporter(Document document) {
        List<Element> values = elements(document, "XMI.exporter");
        if (!values.isEmpty()) return values.get(0).getTextContent().trim();
        return "Enterprise Architect";
    }

    private static String exporterVersion(Document document) {
        List<Element> values = elements(document, "XMI.exporterVersion");
        return values.isEmpty() ? "" : values.get(0).getTextContent().trim();
    }

    private static String operationKey(String tableId, String operationName) {
        return firstNonBlank(tableId, "").toUpperCase(Locale.ROOT) + "|"
                + firstNonBlank(operationName, "").toUpperCase(Locale.ROOT);
    }

    private static String styleExValue(String styleEx, String key) {
        if (styleEx == null || styleEx.isBlank()) return "";
        Matcher matcher = Pattern.compile("(?i)(?:^|;)" + Pattern.quote(key) + "=([^;]*)")
                .matcher(styleEx);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String xmiId(Element element) {
        return firstNonBlank(attribute(element, "xmi.id"), attributeEndingWith(element, "id"));
    }

    private static String attribute(Element element, String name) {
        if (element == null) return "";
        if (element.hasAttribute(name)) return element.getAttribute(name);
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String local = attribute.getLocalName();
            if (name.equals(attribute.getNodeName()) || name.equals(local)) return attribute.getNodeValue();
        }
        return "";
    }

    private static String attributeEndingWith(Element element, String suffix) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (attribute.getNodeName().endsWith(suffix)) return attribute.getNodeValue();
        }
        return "";
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName().replaceFirst("^.*:", "")
                : element.getLocalName();
    }

    private static String sanitizeIdentifier(String value, String fallback) {
        String candidate = firstNonBlank(value, fallback).trim().toUpperCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", "_")
                .replace('-', '_')
                .replace('/', '_')
                .replaceAll("[^A-Z0-9_$#]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (candidate.isBlank()) return fallback == null ? "" : fallback;
        if (Character.isDigit(candidate.charAt(0))) candidate = "T_" + candidate;
        return candidate;
    }

    private static boolean truthy(String value) {
        return value != null && Set.of("1", "TRUE", "YES", "Y")
                .contains(value.trim().toUpperCase(Locale.ROOT));
    }

    private static int nonNegativeInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(firstNonBlank(value, Integer.toString(fallback)).trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Integer nullableNonNegativeInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeDocumentation(String value) {
        if (value == null || value.isBlank()) return "";
        return value
                .replaceAll("(?is)<br\\s*/?>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeCheckExpression(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        Matcher matcher = CHECK_WRAPPER.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private enum OperationKind {
        PRIMARY_KEY, FOREIGN_KEY, UNIQUE_KEY, INDEX, UNIQUE_INDEX, CHECK, OTHER
    }

    private record EaTable(
            String xmiId,
            String schema,
            String name,
            String description,
            List<EaColumn> columns,
            List<EaOperation> operations) { }

    private record EaColumn(
            String name,
            DataType dataType,
            boolean nullable,
            String defaultValue,
            String description,
            boolean identity,
            int position,
            String generatedExpression) { }

    private record EaOperation(
            String name,
            String stereotype,
            List<EaParameter> parameters,
            Map<String, String> tags) { }

    private record EaParameter(String name, int position, SortDirection direction) { }

    private record EaAssociation(
            String sourceTableId,
            String targetTableId,
            String sourceOperation,
            String targetOperation,
            String sourceTableName,
            String targetTableName,
            List<ColumnPair> columnPairs,
            Map<String, String> tags) { }

    private record ColumnPair(String source, String target) { }
}
