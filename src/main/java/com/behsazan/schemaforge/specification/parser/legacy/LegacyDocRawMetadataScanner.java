package com.behsazan.schemaforge.specification.parser.legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative fallback for old binary DOC files whose visible header text is
 * present in the OLE container as UTF-16LE but is not exposed by HWPF ranges.
 *
 * <p>The scanner never guesses a title. It only returns bounded text windows
 * anchored by explicit Word labels such as "نام جدول" or "نام موجوديت".</p>
 */
final class LegacyDocRawMetadataScanner {
    private static final long MAX_SCAN_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_MATCHES_PER_LABEL = 8;
    private static final int BYTES_BEFORE_LABEL = 384;
    private static final int BYTES_AFTER_LABEL = 1536;

    private static final List<String> LABELS = List.of(
            "نام جدول",
            "نام موجودیت",
            "نام موجوديت"
    );

    private static final List<String> STOP_LABELS = List.of(
            "نام جدول",
            "نام صفت",
            "نام فیلد",
            "نام فيلد",
            "تاریخ اصلاح",
            "تاريخ اصلاح",
            "تاریخ ایجاد",
            "تاريخ ايجاد",
            "صفحه",
            "پروژه سامانه",
            "شرح پروژه"
    );

    private static final Pattern TABLE_NAME = Pattern.compile(
            "(?iu)نام\\s*جدول\\s*[:：]?\\s*[.\\s]*([A-Za-z][A-Za-z0-9_$#]*(?:\\s*\\.\\s*[A-Za-z][A-Za-z0-9_$#]*)*)"
    );
    private static final Pattern ENTITY_NAME = Pattern.compile(
            "(?iu)(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*[یي]\\s*ت\\s*[:：]?\\s*(.+?)"
                    + "(?=\\s+(?:صفحه(?:\\s|[:：])|PAGE\\b|NUMPAGES\\b|نام\\s*صفت|نام\\s*فیلد|"
                    + "نام\\s*فيلد|تاریخ(?:\\s|[:：])|تاريخ(?:\\s|[:：])|فرم\\s*/?\\s*نمودار|نحوه\\s*انتقال|محیط\\s*[:：]|محيط\\s*[:：]|آرشیو\\s*[:：]|آرشيو\\s*[:：]|پروژه\\s*سامانه)|$)"
    );

    private LegacyDocRawMetadataScanner() {
    }

    static String extract(Path sourceFile) throws IOException {
        long size = Files.size(sourceFile);
        if (size <= 0L || size > MAX_SCAN_BYTES) {
            return "";
        }

        byte[] bytes = Files.readAllBytes(sourceFile);
        Set<String> blocks = new LinkedHashSet<>();
        for (String label : LABELS) {
            byte[] needle = label.getBytes(StandardCharsets.UTF_16LE);
            int from = 0;
            int matches = 0;
            while (matches < MAX_MATCHES_PER_LABEL) {
                int index = indexOf(bytes, needle, from);
                if (index < 0) {
                    break;
                }
                String block = decodeWindow(bytes, index);
                if (!block.isBlank()) {
                    blocks.add(block);
                }
                from = index + needle.length;
                matches++;
            }
        }
        return blocks.stream()
                .min(java.util.Comparator.comparingInt(String::length))
                .map(TextNormalizer::cleanBlock)
                .orElse("");
    }

    private static String decodeWindow(byte[] bytes, int labelIndex) {
        int parity = labelIndex & 1;
        int start = Math.max(parity, labelIndex - BYTES_BEFORE_LABEL);
        if ((start & 1) != parity) {
            start++;
        }
        int end = Math.min(bytes.length, labelIndex + BYTES_AFTER_LABEL);
        if ((end & 1) != parity) {
            end--;
        }
        if (((end - start) & 1) != 0) {
            end--;
        }
        if (end <= start) {
            return "";
        }

        String decoded = new String(bytes, start, end - start, StandardCharsets.UTF_16LE);
        decoded = sanitize(decoded);
        String cleaned = TextNormalizer.cleanBlock(decoded);
        if (cleaned.isBlank()) {
            return "";
        }

        int anchor = firstLabelIndex(cleaned);
        if (anchor < 0) {
            return "";
        }
        int stop = firstStopIndex(cleaned, anchor + 1);
        String bounded = stop > anchor ? cleaned.substring(anchor, stop) : cleaned.substring(anchor);
        return canonicalizeMetadata(bounded);
    }

    private static String canonicalizeMetadata(String value) {
        String compact = TextNormalizer.compactForMatching(value);
        Matcher entityMatcher = ENTITY_NAME.matcher(compact);
        if (!entityMatcher.find()) {
            return "";
        }
        String candidate = TextNormalizer.compactForMatching(entityMatcher.group(1))
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        if (candidate.isBlank()
                || candidate.length() > 180
                || candidate.equals("نام")
                || candidate.equals("جدول")
                || candidate.equals("نام جدول")
                || candidate.startsWith("تاریخ ایجاد")
                || candidate.startsWith("تاریخ اصلاح")
                || candidate.startsWith("تاريخ ايجاد")
                || candidate.startsWith("تاريخ اصلاح")
                || candidate.startsWith("صفحه")
                || candidate.contains("نام صفت")
                || candidate.contains("نام فیلد")
                || candidate.contains("نام فيلد")) {
            return "";
        }
        boolean hasPersian = candidate.codePoints().anyMatch(codePoint ->
                Character.isLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC
        );
        if (!hasPersian) {
            return "";
        }

        String tableName = "";
        Matcher tableMatcher = TABLE_NAME.matcher(compact);
        if (tableMatcher.find()) {
            tableName = TextNormalizer.normalizeTechnicalName(tableMatcher.group(1));
            int dot = tableName.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < tableName.length()) {
                tableName = tableName.substring(dot + 1);
            }
            String upper = tableName.toUpperCase(Locale.ROOT);
            if (upper.equals("TABLE") || upper.equals("NAME") || upper.equals("DB2")) {
                tableName = "";
            }
        }

        StringBuilder canonical = new StringBuilder(128);
        if (!tableName.isBlank()) {
            canonical.append("نام جدول: ").append(tableName).append('\n');
        }
        canonical.append("نام موجودیت: ").append(candidate);
        return TextNormalizer.cleanBlock(canonical.toString());
    }

    private static int firstLabelIndex(String value) {
        int best = -1;
        for (String label : LABELS) {
            int index = value.indexOf(label);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static int firstStopIndex(String value, int fromIndex) {
        List<Integer> positions = new ArrayList<>();
        for (String stopLabel : STOP_LABELS) {
            int index = value.indexOf(stopLabel, fromIndex);
            if (index >= 0) {
                positions.add(index);
            }
        }
        return positions.stream().min(Integer::compareTo).orElse(-1);
    }

    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == '\t') {
                out.append(ch);
            } else if (Character.isISOControl(ch) || ch == '\u0000') {
                out.append(' ');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static int indexOf(byte[] source, byte[] target, int fromIndex) {
        if (target.length == 0) {
            return Math.max(0, fromIndex);
        }
        int last = source.length - target.length;
        for (int index = Math.max(0, fromIndex); index <= last; index++) {
            int offset = 0;
            while (offset < target.length && source[index + offset] == target[offset]) {
                offset++;
            }
            if (offset == target.length) {
                return index;
            }
        }
        return -1;
    }
}
