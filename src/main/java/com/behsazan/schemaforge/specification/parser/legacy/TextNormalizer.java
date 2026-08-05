package com.behsazan.schemaforge.specification.parser.legacy;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class TextNormalizer {
    static final char CELL_PARAGRAPH_SEPARATOR = '\u001E';
    private static final Pattern MULTI_SPACE = Pattern.compile("[\\p{Zs}\\t\\x0B\\f]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MULTI_LINE = Pattern.compile("(?:\\s*\\R\\s*)+");
    private static final Pattern TECHNICAL_NAME_WITH_SPACES = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$# ]*");
    private static final Pattern TECHNICAL_NAME = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$#]*");

    private TextNormalizer() {
    }

    static String cleanCell(String value) {
        if (value == null) {
            return "";
        }
        String s = canonicalizePersianLetters(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replace('\u0007', ' ')
                .replace(CELL_PARAGRAPH_SEPARATOR, ' ')
                .replace("\u0640", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\u00A0', ' ')
                .replace('\u200C', ' ')
                .replace('\u200D', ' ')
                .replace('\uFEFF', ' ');
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    static String cleanBlock(String value) {
        if (value == null) {
            return "";
        }
        String s = canonicalizePersianLetters(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replace('\u0007', ' ')
                .replace(CELL_PARAGRAPH_SEPARATOR, '\n')
                .replace("\u0640", "")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace('\u200C', ' ')
                .replace('\u200D', ' ')
                .replace('\uFEFF', ' ');
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_LINE.matcher(s).replaceAll("\n");
        return s.trim();
    }

    static String compactForMatching(String value) {
        return cleanCell(value)
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String normalizeTechnicalName(String raw) {
        String cleaned = cleanCell(raw);
        if (cleaned.isEmpty()) {
            return "";
        }
        if (TECHNICAL_NAME.matcher(cleaned).matches()) {
            return cleaned;
        }
        if (TECHNICAL_NAME_WITH_SPACES.matcher(cleaned).matches()) {
            String compact = cleaned.replace(" ", "");
            if (TECHNICAL_NAME.matcher(compact).matches()) {
                return compact;
            }
        }
        return cleaned;
    }

    static boolean isTechnicalFieldName(String raw) {
        String normalized = normalizeTechnicalName(raw);
        return TECHNICAL_NAME.matcher(normalized).matches() && normalized.length() >= 2;
    }

    static boolean isTechnicalIdentifier(String raw) {
        return TECHNICAL_NAME.matcher(cleanCell(raw)).matches();
    }

    static List<String> splitTokens(String raw) {
        String value = cleanCell(raw);
        if (value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("[,،;/|\\s]+", -1);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (!token.isEmpty()) {
                result.add(token.toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableList(result);
    }


    static List<String> splitCellParagraphs(String raw) {
        if (raw == null) {
            return List.of();
        }
        String[] parts = raw.split(Pattern.quote(String.valueOf(CELL_PARAGRAPH_SEPARATOR)), -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(cleanCell(part));
        }
        return Collections.unmodifiableList(result);
    }

    static boolean hasStructuredCellParagraphs(String raw) {
        return raw != null && raw.indexOf(CELL_PARAGRAPH_SEPARATOR) >= 0;
    }

    static String joinCellParagraphs(List<String> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }
        return String.join(String.valueOf(CELL_PARAGRAPH_SEPARATOR), paragraphs);
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static String toLatinDigits(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            int digit = switch (ch) {
                case '۰', '٠' -> 0;
                case '۱', '١' -> 1;
                case '۲', '٢' -> 2;
                case '۳', '٣' -> 3;
                case '۴', '٤' -> 4;
                case '۵', '٥' -> 5;
                case '۶', '٦' -> 6;
                case '۷', '٧' -> 7;
                case '۸', '٨' -> 8;
                case '۹', '٩' -> 9;
                default -> -1;
            };
            out.append(digit >= 0 ? (char) ('0' + digit) : ch);
        }
        return out.toString();
    }

    private static String canonicalizePersianLetters(String value) {
        return value
                .replace('\u064A', '\u06CC') // Arabic yeh -> Persian yeh
                .replace('\u0649', '\u06CC') // alef maksura -> Persian yeh
                .replace('\u06D2', '\u06CC') // Urdu yeh -> Persian yeh
                .replace('\u0643', '\u06A9') // Arabic kaf -> Persian kaf
                .replace('\u0629', '\u0647') // ta marbuta -> heh
                .replace('\u06C0', '\u0647'); // heh with yeh above -> heh
    }

    static String uppercaseEnglish(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
