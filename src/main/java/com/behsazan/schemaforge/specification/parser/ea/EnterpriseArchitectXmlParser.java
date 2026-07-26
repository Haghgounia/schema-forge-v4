package com.behsazan.schemaforge.specification.parser.ea;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal EA/XMI importer for UML classes and their owned attributes. */
public final class EnterpriseArchitectXmlParser {
    public DatabaseSchema parse(String fileName, InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(inputStream);

            String schemaName = firstNonBlank(
                    document.getDocumentElement().getAttribute("name"),
                    baseName(fileName),
                    "EA_SCHEMA");
            schemaName = sanitize(schemaName);

            DatabaseSchema.Builder schema = DatabaseSchema.builder(schemaName)
                    .metadata("source.fileName", fileName)
                    .metadata("source.format", "EA-XMI");

            List<Element> classes = findElements(document, "packagedElement", "Class");
            if (classes.isEmpty()) {
                classes = findElements(document, "Class", null);
            }
            if (classes.isEmpty()) {
                throw new IllegalArgumentException("No UML Class elements found in EA XML/XMI: " + fileName);
            }

            for (Element classElement : classes) {
                String tableName = sanitize(firstNonBlank(classElement.getAttribute("name"), "UNNAMED_TABLE"));
                Table.Builder table = Table.builder(schemaName, tableName);
                List<Element> attributes = childElements(classElement, "ownedAttribute", "Attribute");
                int ordinal = 1;
                for (Element attribute : attributes) {
                    String columnName = sanitize(firstNonBlank(attribute.getAttribute("name"), "COLUMN_" + ordinal));
                    String typeName = resolveType(attribute);
                    table.addColumn(new Column(
                            Identifier.of(columnName),
                            mapType(typeName),
                            !"1".equals(attribute.getAttribute("lower")),
                            new DefaultValue(null),
                            Description.empty(),
                            false,
                            ordinal++));
                }
                if (ordinal == 1) {
                    table.addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)));
                }
                schema.addTable(table.build());
            }
            return schema.build();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse EA XML/XMI: " + fileName, exception);
        }
    }

    private static List<Element> findElements(Document document, String localName, String xmiTypeSuffix) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = document.getElementsByTagName(localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) continue;
            if (xmiTypeSuffix == null || attributeEndingWith(element, "type").endsWith(xmiTypeSuffix)) result.add(element);
        }
        return result;
    }

    private static List<Element> childElements(Element parent, String... names) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) continue;
            String local = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            for (String name : names) if (name.equals(local)) result.add(element);
        }
        return result;
    }

    private static String resolveType(Element attribute) {
        String type = firstNonBlank(attribute.getAttribute("type"), attributeEndingWith(attribute, "type"));
        for (Element child : childElements(attribute, "type")) {
            type = firstNonBlank(child.getAttribute("href"), child.getAttribute("name"), type);
        }
        if (type.contains("#")) type = type.substring(type.lastIndexOf('#') + 1);
        return firstNonBlank(type, "String");
    }

    private static DataType mapType(String raw) {
        String type = raw.toLowerCase(Locale.ROOT);
        if (type.contains("string") || type.contains("char")) return DataType.varchar("VARCHAR2", 255);
        if (type.contains("date") || type.contains("time")) return DataType.simple("TIMESTAMP");
        if (type.contains("bool")) return DataType.simple("BOOLEAN");
        if (type.contains("int") || type.contains("long") || type.contains("number") || type.contains("decimal")) return DataType.numeric("NUMBER", 19, 0);
        return DataType.varchar("VARCHAR2", 255);
    }

    private static String attributeEndingWith(Element element, String suffix) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            if (attribute.getNodeName().endsWith(suffix)) return attribute.getNodeValue();
        }
        return "";
    }

    private static String sanitize(String value) {
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_$#]", "_").replaceAll("_+", "_");
        if (normalized.isBlank()) return "UNNAMED";
        if (Character.isDigit(normalized.charAt(0))) normalized = "T_" + normalized;
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
