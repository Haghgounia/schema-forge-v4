package com.behsazan.schemaforge.glossary;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict, bounded reader for the CSV emitted by GlossaryReportWriter (not a general CSV importer). */
final class GlossaryReportCsvParser {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 20_000;
    private static final List<String> HEADER = List.of(
            "source_name", "object_type", "table_name", "column_name", "identifier",
            "unknown_term", "token_position", "review_status");

    private GlossaryReportCsvParser() { }

    static ParsedReport parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Glossary CSV must contain 1 to 5242880 bytes");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Glossary CSV must use valid UTF-8", exception);
        }
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> rows = splitCsv(content);
        if (rows.isEmpty() || !HEADER.equals(rows.getFirst())) {
            throw new IllegalArgumentException("Invalid glossary report CSV header or column order");
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new IllegalArgumentException("Glossary report contains too many rows");
        }
        List<Occurrence> occurrences = new ArrayList<>();
        int skipped = 0;
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != HEADER.size()) {
                throw new IllegalArgumentException("Invalid glossary CSV field count at data row " + index);
            }
            String status = row.get(7);
            if (!List.of("NEW", "PENDING_APPROVAL", "REJECTED").contains(status)) {
                throw new IllegalArgumentException("Invalid review_status at data row " + index);
            }
            if (!"NEW".equals(status)) {
                skipped++;
                continue;
            }
            String source = bounded(row.get(0), 1024, "source_name", index, true);
            String type = bounded(row.get(1), 12, "object_type", index, true);
            if (!"TABLE".equals(type) && !"COLUMN".equals(type)) {
                throw new IllegalArgumentException("Invalid object_type at data row " + index);
            }
            String table = bounded(row.get(2), 256, "table_name", index, true);
            String column = bounded(row.get(3), 256, "column_name", index, "COLUMN".equals(type));
            String identifier = bounded(row.get(4), 512, "identifier", index, true);
            String term = bounded(row.get(5), 256, "unknown_term", index, true);
            if (!term.equals(GlossaryNormalizer.key(term)) || term.contains("_")) {
                throw new IllegalArgumentException("NEW glossary report terms must be canonical atomic tokens at data row "
                        + index);
            }
            final int position;
            try {
                position = Integer.parseInt(row.get(6));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid token_position at data row " + index, exception);
            }
            List<String> tokens = GlossaryNormalizer.identifierTokens(identifier);
            if (position < 1 || position > tokens.size() || !term.equals(tokens.get(position - 1))) {
                throw new IllegalArgumentException("Token does not match identifier and token_position at data row "
                        + index);
            }
            if ("TABLE".equals(type) && !column.isEmpty()) {
                throw new IllegalArgumentException("A TABLE occurrence must not have column_name at row " + index);
            }
            if ("COLUMN".equals(type) && !identifier.equals(column)) {
                throw new IllegalArgumentException("A COLUMN identifier must match column_name at row " + index);
            }
            if ("TABLE".equals(type) && !identifier.equals(table)) {
                throw new IllegalArgumentException("A TABLE identifier must match table_name at row " + index);
            }
            occurrences.add(new Occurrence(index, source, type, table, column, identifier, term, position));
        }
        return new ParsedReport(rows.size() - 1, skipped, List.copyOf(occurrences));
    }

    private static String bounded(String value, int max, String field, int row, boolean required) {
        if (value.length() > max || (required && value.isBlank())) {
            throw new IllegalArgumentException("Invalid " + field + " at data row " + row);
        }
        return value;
    }

    private static List<List<String>> splitCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean atFieldStart = true;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == ',' || ch == '\r' || ch == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                atFieldStart = true;
                if (ch == '\r' || ch == '\n') {
                    if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                    rows.add(List.copyOf(fields));
                    if (rows.size() > MAX_ROWS + 1) {
                        throw new IllegalArgumentException("Glossary CSV exceeds 20000 data rows");
                    }
                    fields.clear();
                }
            } else if (ch == '"' && atFieldStart) {
                quoted = true;
                atFieldStart = false;
            } else if (closedQuote || ch == '"') {
                throw new IllegalArgumentException("Malformed CSV quotation");
            } else {
                field.append(ch);
                atFieldStart = false;
            }
            if (field.length() > 4096) throw new IllegalArgumentException("Glossary CSV field is too large");
        }
        if (quoted) throw new IllegalArgumentException("Unterminated glossary CSV quotation");
        if (closedQuote || !atFieldStart || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(List.copyOf(fields));
        }
        return rows;
    }

    record Occurrence(int rowNumber, String source, String objectType, String tableName,
                      String columnName, String identifier, String term, int position) { }

    record ParsedReport(int sourceRows, int skippedNonNew, List<Occurrence> newOccurrences) { }
}
