package com.behsazan.schemaforge.specification.parser.legacy;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads plain text directly from selected OOXML parts of a DOCX package.
 *
 * <p>The extractor is a defensive fallback for cases where higher-level Apache POI text
 * extraction does not expose all metadata needed by the legacy parser. It reads the main
 * document and header XML parts with StAX, disables DTD and external-entity processing,
 * preserves structural separators, and enforces a bounded output size. It intentionally
 * performs no table-specification interpretation.</p>
 */
final class DocxXmlTextExtractor {
    private static final int MAX_XML_TEXT_CHARS = 64 * 1024 * 1024;

    private DocxXmlTextExtractor() {
    }

    static String extractDocumentText(Path sourceFile) throws IOException {
        try (ZipFile zip = new ZipFile(sourceFile.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            return entry == null ? "" : extractEntry(zip, entry);
        }
    }

    static String extractHeaderText(Path sourceFile) throws IOException {
        try (ZipFile zip = new ZipFile(sourceFile.toFile())) {
            List<ZipEntry> headers = new ArrayList<>();
            zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().matches("word/header[0-9]+\\.xml"))
                    .forEach(headers::add);
            headers.sort(Comparator.comparing(ZipEntry::getName));

            StringBuilder out = new StringBuilder(4096);
            for (ZipEntry header : headers) {
                appendSeparated(out, extractEntry(zip, header));
            }
            return TextNormalizer.cleanBlock(out.toString());
        }
    }

    private static String extractEntry(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_XML_TEXT_CHARS * 8L) {
            throw new IOException("DOCX XML part is too large: " + entry.getName());
        }

        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setProperty(factory, XMLInputFactory.IS_COALESCING, true);

        StringBuilder out = new StringBuilder((int) Math.min(Math.max(entry.getSize(), 1024L), 1_000_000L));
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = reader.getLocalName();
                        if ("t".equals(local) || "instrText".equals(local) || "delText".equals(local)) {
                            appendLimited(out, reader.getElementText());
                        } else if ("tab".equals(local)) {
                            appendLimited(out, "\t");
                        } else if ("br".equals(local) || "cr".equals(local)) {
                            appendLimited(out, "\n");
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String local = reader.getLocalName();
                        if ("tc".equals(local)) {
                            appendLimited(out, "\t");
                        } else if ("p".equals(local) || "tr".equals(local) || "tbl".equals(local)) {
                            appendLimited(out, "\n");
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Cannot read DOCX XML part: " + entry.getName(), e);
        }
        return TextNormalizer.cleanBlock(out.toString());
    }

    private static void appendSeparated(StringBuilder target, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            appendLimited(target, "\n");
        }
        appendLimited(target, value);
    }

    private static void appendLimited(StringBuilder target, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        if ((long) target.length() + value.length() > MAX_XML_TEXT_CHARS) {
            throw new IOException("Extracted DOCX XML text exceeds safety limit.");
        }
        target.append(value);
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some StAX implementations do not expose every optional property.
        }
    }
}
