package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Severity;

/**
 * Recovers the separate legacy RTL9 "indexes" grid and routes its evidence back to
 * the already extracted field rows.
 *
 * <p>The old Facility documents keep index definitions in a second Word table whose
 * header is normally {@code ردیف | نوع شاخص | نام فیلد}.  The field-definition grid
 * therefore contains no usable PK/index tokens.  This resolver is intentionally
 * conservative: only explicit primary-key, unique-index, non-unique-index, or generic
 * normal-index labels are accepted, and every referenced field must resolve to an extracted
 * technical column before the row is applied. Missing type labels and bare composite-key
 * labels remain fail-closed because their uniqueness semantics are not stated.</p>
 *
 * <p>No foreign-key/dependency recovery is performed here.</p>
 */
final class LegacyRtl9IndexSectionResolver {
    private static final Pattern INDEX_TOKEN = Pattern.compile(
            "(?i)^(UIX|IX|IDX|INDEX|I|X)(\\d*)(?:[_:,](\\d+))?$");
    private static final Pattern KEY_TOKEN = Pattern.compile(
            "(?i)^(PK|PFK)(\\d*)(?:[_:,](\\d+))?$");
    private static final Pattern ENGLISH_AND = Pattern.compile("(?i)\\band\\b");
    private static final Pattern SAFE_REMAINDER = Pattern.compile(
            "^[\\s,،;:+|/&\\-()\\[\\]{}\\u200c\\u200f\\u202a-\\u202eو]*$");

    private LegacyRtl9IndexSectionResolver() {
    }

    static List<ColumnDefinition> apply(
            List<ColumnDefinition> sourceColumns,
            List<List<List<String>>> tables,
            List<ExtractionWarning> warnings) {
        if (sourceColumns == null || sourceColumns.isEmpty() || tables == null || tables.isEmpty()) {
            return sourceColumns == null ? List.of() : List.copyOf(sourceColumns);
        }

        List<SectionEntry> entries = extractEntries(sourceColumns, tables, warnings);
        if (entries.isEmpty()) {
            return List.copyOf(sourceColumns);
        }

        List<ColumnDefinition> columns = new ArrayList<>(sourceColumns);
        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            columnIndex.putIfAbsent(normalizeTechnical(columns.get(index).fieldName()), index);
        }

        applyPrimaryKey(entries, columns, columnIndex, warnings);
        applyIndexes(entries, columns, columnIndex);
        return List.copyOf(columns);
    }

    static List<SectionEntry> extractEntries(
            List<ColumnDefinition> columns,
            List<List<List<String>>> tables,
            List<ExtractionWarning> warnings) {
        Map<String, String> knownColumns = new LinkedHashMap<>();
        for (ColumnDefinition column : columns) {
            String normalized = normalizeTechnical(column.fieldName());
            if (!normalized.isBlank()) {
                knownColumns.putIfAbsent(normalized, column.fieldName());
            }
        }

        List<SectionEntry> entries = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<List<String>> table = tables.get(tableIndex);
            Header header = findHeader(table);
            if (header == null) {
                continue;
            }
            for (int rowIndex = header.rowIndex() + 1; rowIndex < table.size(); rowIndex++) {
                List<String> row = table.get(rowIndex);
                String typeRaw = cell(row, header.typeIndex());
                String fieldsRaw = cell(row, header.fieldIndex());
                if (typeRaw.isBlank() && fieldsRaw.isBlank()) {
                    continue;
                }
                if (fieldsRaw.isBlank()) {
                    continue;
                }

                Kind kind = classify(typeRaw);
                if (kind == null) {
                    String warningCode;
                    String warningMessage;
                    if (typeRaw.isBlank()) {
                        warningCode = "LEGACY_INDEX_SECTION_TYPE_NOT_PRESENT";
                        warningMessage = "Index-section row has a field reference but no index type; uniqueness cannot be inferred, so the row was not applied.";
                    } else if (isAmbiguousCompositeKeyLabel(typeRaw)) {
                        warningCode = "LEGACY_INDEX_SECTION_COMPOSITE_KEY_AMBIGUOUS";
                        warningMessage = "Index-section row is labeled only as a composite key; primary/unique/non-unique semantics are ambiguous, so the row was not applied.";
                    } else {
                        warningCode = "LEGACY_INDEX_SECTION_TYPE_UNRECOGNIZED";
                        warningMessage = "Index-section row has a field reference but an unsupported index type; the row was not applied.";
                    }
                    warnings.add(new ExtractionWarning(
                            Severity.WARNING,
                            warningCode,
                            null,
                            rowIndex + 1,
                            warningMessage,
                            typeRaw + " | " + fieldsRaw
                    ));
                    continue;
                }

                ResolvedMembers resolved = resolveMembers(fieldsRaw, knownColumns);
                if (resolved.members().isEmpty() || !resolved.complete()) {
                    warnings.add(new ExtractionWarning(
                            Severity.WARNING,
                            "LEGACY_INDEX_SECTION_FIELD_UNRESOLVED",
                            null,
                            rowIndex + 1,
                            "Index-section field list could not be mapped exactly to extracted technical fields; the row was not applied.",
                            fieldsRaw
                    ));
                    continue;
                }

                entries.add(new SectionEntry(
                        tableIndex,
                        rowIndex,
                        kind,
                        typeRaw,
                        fieldsRaw,
                        resolved.members()
                ));
            }
        }
        return List.copyOf(entries);
    }

    private static void applyPrimaryKey(
            List<SectionEntry> entries,
            List<ColumnDefinition> columns,
            Map<String, Integer> columnIndex,
            List<ExtractionWarning> warnings) {
        List<String> sectionPrimary = entries.stream()
                .filter(entry -> entry.kind() == Kind.PRIMARY_KEY)
                .flatMap(entry -> entry.members().stream())
                .distinct()
                .toList();
        if (sectionPrimary.isEmpty()) {
            return;
        }

        List<String> existingPrimary = columns.stream()
                .filter(column -> column.keys().stream().anyMatch(LegacyRtl9IndexSectionResolver::isPrimaryToken))
                .sorted(Comparator.comparingInt(ColumnDefinition::sequence))
                .map(ColumnDefinition::fieldName)
                .distinct()
                .toList();

        if (!existingPrimary.isEmpty()) {
            if (!sameMembers(existingPrimary, sectionPrimary)) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "LEGACY_INDEX_SECTION_PRIMARY_KEY_CONFLICT",
                        null,
                        null,
                        "The separate index section declares a primary key that conflicts with primary-key evidence already present in field rows; existing field-row evidence was retained.",
                        "fieldRows=" + String.join(",", existingPrimary)
                                + " | indexSection=" + String.join(",", sectionPrimary)
                ));
            }
            return;
        }

        for (int position = 0; position < sectionPrimary.size(); position++) {
            String member = sectionPrimary.get(position);
            Integer index = columnIndex.get(normalizeTechnical(member));
            if (index == null) {
                continue;
            }
            ColumnDefinition column = columns.get(index);
            String token = "PK:" + (position + 1);
            columns.set(index, withKeyToken(column, token));
        }
    }

    private static void applyIndexes(
            List<SectionEntry> entries,
            List<ColumnDefinition> columns,
            Map<String, Integer> columnIndex) {
        List<ExistingIndex> existing = existingIndexes(columns);
        int group = nextIndexGroup(existing);

        for (SectionEntry entry : entries) {
            if (entry.kind() == Kind.PRIMARY_KEY) {
                continue;
            }
            boolean unique = entry.kind() == Kind.UNIQUE_INDEX;
            if (hasEquivalentIndex(existing, entry.members(), unique)) {
                continue;
            }

            String prefix = unique ? "UIX" : "IX";
            String groupName = prefix + group++;
            List<String> appliedMembers = new ArrayList<>();
            for (int position = 0; position < entry.members().size(); position++) {
                String member = entry.members().get(position);
                Integer index = columnIndex.get(normalizeTechnical(member));
                if (index == null) {
                    continue;
                }
                ColumnDefinition column = columns.get(index);
                String token = groupName + ":" + (position + 1);
                columns.set(index, withIndexToken(column, token));
                appliedMembers.add(column.fieldName());
            }
            if (!appliedMembers.isEmpty()) {
                existing.add(new ExistingIndex(groupName, unique, List.copyOf(appliedMembers)));
            }
        }
    }

    private static Header findHeader(List<List<String>> table) {
        if (table == null) {
            return null;
        }
        int limit = Math.min(table.size(), 4);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            List<String> row = table.get(rowIndex);
            int typeIndex = -1;
            int fieldIndex = -1;
            for (int cellIndex = 0; cellIndex < row.size(); cellIndex++) {
                String compact = compactPersian(cell(row, cellIndex));
                if (compact.contains("نوعشاخص") || compact.equals("indextype")) {
                    typeIndex = cellIndex;
                }
                if (compact.contains("نامفیلد") || compact.contains("نامفيلد")
                        || compact.equals("fieldname") || compact.equals("columnname")) {
                    fieldIndex = cellIndex;
                }
            }
            if (typeIndex >= 0 && fieldIndex >= 0) {
                return new Header(rowIndex, typeIndex, fieldIndex);
            }
        }
        return null;
    }

    private static Kind classify(String raw) {
        String normalized = compactPersian(raw);
        String latin = normalized.toLowerCase(Locale.ROOT);
        if (normalized.contains("کلیداصلی") || normalized.contains("كليداصلي")
                || latin.contains("primarykey") || latin.equals("pk")) {
            return Kind.PRIMARY_KEY;
        }
        if (normalized.contains("غیریکتا") || normalized.contains("غيریکتا")
                || normalized.contains("غيريکتا") || normalized.contains("غیرمنحصر")
                || latin.contains("nonunique") || latin.contains("non-unique")) {
            return Kind.NON_UNIQUE_INDEX;
        }
        if ((normalized.contains("یکتا") || normalized.contains("يکتا")
                || latin.contains("unique"))
                && !normalized.contains("غیر") && !normalized.contains("غير")
                && !latin.contains("nonunique") && !latin.contains("non-unique")) {
            return Kind.UNIQUE_INDEX;
        }
        if (isExplicitGenericNonUniqueIndexLabel(normalized, latin)) {
            return Kind.NON_UNIQUE_INDEX;
        }
        return null;
    }

    private static boolean isExplicitGenericNonUniqueIndexLabel(String normalized, String latin) {
        if (normalized.equals("معمولی") || normalized.equals("شاخصمعمولی")) {
            return true;
        }
        if (normalized.matches("ایندکس\\d*") || normalized.matches("اندکس\\d*")) {
            return true;
        }
        return latin.matches("(?:index|idx|ix)\\d*");
    }

    private static boolean isAmbiguousCompositeKeyLabel(String raw) {
        String normalized = compactPersian(raw);
        return normalized.equals("کلیدترکیبی") || normalized.equals("كليدتركيبي")
                || normalized.equals("compositekey");
    }

    private static ResolvedMembers resolveMembers(String raw, Map<String, String> knownColumns) {
        String source = raw == null ? "" : raw.trim();
        if (source.isBlank()) {
            return new ResolvedMembers(List.of(), false);
        }

        List<Match> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : knownColumns.entrySet()) {
            String needle = entry.getKey();
            int from = 0;
            while (from < source.length()) {
                int found = indexOfIgnoreCase(source, needle, from);
                if (found < 0) {
                    break;
                }
                candidates.add(new Match(found, found + needle.length(), entry.getValue()));
                from = found + 1;
            }
        }
        candidates.sort(Comparator
                .comparingInt((Match match) -> match.start())
                .thenComparing((Match match) -> -(match.end() - match.start())));

        List<Match> selected = new ArrayList<>();
        for (Match candidate : candidates) {
            boolean overlaps = selected.stream().anyMatch(existing ->
                    candidate.start() < existing.end() && existing.start() < candidate.end());
            if (!overlaps) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(Match::start));

        LinkedHashSet<String> members = new LinkedHashSet<>();
        StringBuilder remainder = new StringBuilder(source);
        for (Match match : selected) {
            members.add(match.fieldName());
            for (int index = match.start(); index < match.end(); index++) {
                remainder.setCharAt(index, ' ');
            }
        }

        String residual = ENGLISH_AND.matcher(remainder.toString()).replaceAll(" ");
        boolean complete = !members.isEmpty() && SAFE_REMAINDER.matcher(residual).matches();
        return new ResolvedMembers(List.copyOf(members), complete);
    }

    private static List<ExistingIndex> existingIndexes(List<ColumnDefinition> columns) {
        Map<String, ExistingIndexBuilder> groups = new LinkedHashMap<>();
        for (ColumnDefinition column : columns) {
            for (String raw : column.indexes()) {
                ParsedIndexToken token = parseIndexToken(raw);
                if (token == null) {
                    continue;
                }
                ExistingIndexBuilder builder = groups.computeIfAbsent(
                        token.group(), ignored -> new ExistingIndexBuilder(token.unique()));
                builder.members().add(new OrderedMember(
                        column.fieldName(), token.position(), column.sequence()));
            }
        }
        List<ExistingIndex> result = new ArrayList<>();
        groups.forEach((group, builder) -> result.add(new ExistingIndex(
                group,
                builder.unique(),
                builder.members().stream()
                        .sorted(Comparator
                                .comparing((OrderedMember member) -> member.position() == null ? Integer.MAX_VALUE : member.position())
                                .thenComparingInt(OrderedMember::sequence))
                        .map(OrderedMember::name)
                        .toList()
        )));
        return result;
    }

    private static ParsedIndexToken parseIndexToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher matcher = INDEX_TOKEN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
        String canonicalPrefix = switch (prefix) {
            case "IDX", "INDEX", "I", "X" -> "IX";
            default -> prefix;
        };
        String number = matcher.group(2);
        String group = canonicalPrefix + (number == null || number.isBlank() ? "1" : number);
        return new ParsedIndexToken(
                group,
                canonicalPrefix.equals("UIX"),
                integer(matcher.group(3))
        );
    }

    private static boolean isPrimaryToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return KEY_TOKEN.matcher(raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "")).matches();
    }

    private static int nextIndexGroup(List<ExistingIndex> indexes) {
        int max = 0;
        for (ExistingIndex index : indexes) {
            Matcher matcher = Pattern.compile("(?i)^(?:UIX|IX)(\\d+)$").matcher(index.group());
            if (matcher.matches()) {
                Integer value = integer(matcher.group(1));
                if (value != null) {
                    max = Math.max(max, value);
                }
            }
        }
        return max + 1;
    }

    private static boolean hasEquivalentIndex(List<ExistingIndex> indexes, List<String> members, boolean unique) {
        return indexes.stream().anyMatch(index -> index.unique() == unique && sameMembers(index.members(), members));
    }

    private static boolean sameMembers(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!normalizeTechnical(left.get(index)).equals(normalizeTechnical(right.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private static ColumnDefinition withKeyToken(ColumnDefinition source, String token) {
        List<String> keys = appendDistinct(source.keys(), token);
        return new ColumnDefinition(
                source.sequence(), source.sourceTableIndex(), source.sourceRowIndex(),
                source.persianTitle(), source.fieldName(), source.fieldNameRaw(),
                source.typeRaw(), source.lengthRaw(), mergeRaw(source.keyRaw(), token), source.indexRaw(),
                source.mandatoryRaw(), source.mandatory(), source.db2TypeRaw(), source.db2LengthRaw(),
                source.referenceOrDefaultRaw(), keys, source.indexes(), source.rawCells()
        );
    }

    private static ColumnDefinition withIndexToken(ColumnDefinition source, String token) {
        List<String> indexes = appendDistinct(source.indexes(), token);
        return new ColumnDefinition(
                source.sequence(), source.sourceTableIndex(), source.sourceRowIndex(),
                source.persianTitle(), source.fieldName(), source.fieldNameRaw(),
                source.typeRaw(), source.lengthRaw(), source.keyRaw(), mergeRaw(source.indexRaw(), token),
                source.mandatoryRaw(), source.mandatory(), source.db2TypeRaw(), source.db2LengthRaw(),
                source.referenceOrDefaultRaw(), source.keys(), indexes, source.rawCells()
        );
    }

    private static List<String> appendDistinct(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        if (copy.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static String mergeRaw(String raw, String token) {
        if (raw == null || raw.isBlank()) {
            return token;
        }
        if (raw.toUpperCase(Locale.ROOT).contains(token.toUpperCase(Locale.ROOT))) {
            return raw;
        }
        return raw.trim() + " " + token;
    }

    private static int indexOfIgnoreCase(String source, String needle, int fromIndex) {
        String sourceUpper = source.toUpperCase(Locale.ROOT);
        String needleUpper = needle.toUpperCase(Locale.ROOT);
        return sourceUpper.indexOf(needleUpper, Math.max(0, fromIndex));
    }

    private static String normalizeTechnical(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String compactPersian(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace("\u200c", "")
                .replaceAll("[\\s()（）:_-]+", "")
                .trim();
    }

    private static String cell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : TextNormalizer.cleanCell(value);
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    enum Kind {
        PRIMARY_KEY,
        UNIQUE_INDEX,
        NON_UNIQUE_INDEX
    }

    record SectionEntry(
            int sourceTableIndex,
            int sourceRowIndex,
            Kind kind,
            String typeRaw,
            String fieldsRaw,
            List<String> members) {
    }

    private record Header(int rowIndex, int typeIndex, int fieldIndex) {
    }

    private record ResolvedMembers(List<String> members, boolean complete) {
    }

    private record Match(int start, int end, String fieldName) {
    }

    private record ParsedIndexToken(String group, boolean unique, Integer position) {
    }

    private record OrderedMember(String name, Integer position, int sequence) {
    }

    private record ExistingIndex(String group, boolean unique, List<String> members) {
    }

    private record ExistingIndexBuilder(boolean unique, List<OrderedMember> members) {
        private ExistingIndexBuilder(boolean unique) {
            this(unique, new ArrayList<>());
        }
    }
}
