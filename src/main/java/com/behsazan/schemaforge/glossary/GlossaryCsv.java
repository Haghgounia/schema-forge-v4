package com.behsazan.schemaforge.glossary;

/** RFC 4180-style CSV encoding with spreadsheet-formula injection protection. */
public final class GlossaryCsv {
    private GlossaryCsv() { }

    public static String row(String... values) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            String value = values[i] == null ? "" : values[i];
            if (!value.isEmpty() && ("=+-@".indexOf(value.charAt(0)) >= 0
                    || value.charAt(0) == '\t' || value.charAt(0) == '\r')) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return csv.append("\r\n").toString();
    }
}
