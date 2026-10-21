package com.behsazan.schemaforge.glossary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Read-only lexical suggestions for stewardship, never a spelling verdict or an automatic correction. */
public final class GlossarySpellingAdvisor {
    private final GlossaryRepository repository;

    public GlossarySpellingAdvisor(GlossaryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Advice advise(String rawTerm, String objectType, int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("limit must be between 1 and 10");
        if (!"TABLE".equals(objectType) && !"COLUMN".equals(objectType)) {
            throw new IllegalArgumentException("objectType must be TABLE or COLUMN");
        }
        String term = GlossaryNormalizer.key(rawTerm);
        if (term.length() > 64 || term.contains("_") || !term.matches("[A-Z0-9]+")) {
            throw new IllegalArgumentException("Provide exactly one normalized atomic term");
        }
        GlossaryLexicon lexicon = repository.approvedLexicon();
        var existing = repository.getByKey(GlossaryTermKind.ATOMIC, term);
        if (existing.isPresent()) {
            String classification = switch (existing.get().status()) {
                case APPROVED -> "KNOWN";
                case CANDIDATE -> "PENDING_APPROVAL";
                case REJECTED -> "REJECTED";
            };
            // Even historic plural records must obey the current singular naming rule.
            String singular = GlossaryInflection.knownSingular(term, lexicon);
            if (singular != null) {
                return pluralAdvice(term, objectType, singular, lexicon);
            }
            return new Advice(term, classification, null, null, List.of(), false);
        }
        String singular = GlossaryInflection.knownSingular(term, lexicon);
        if (singular != null) {
            return pluralAdvice(term, objectType, singular, lexicon);
        }
        if (term.length() < 4 || !term.matches("[A-Z]+")) {
            return new Advice(term, "NEW", null, null, List.of(), false);
        }
        List<Suggestion> matches = new ArrayList<>();
        int page = 0;
        GlossaryPage batch;
        do {
            batch = repository.search(null, GlossaryTermKind.ATOMIC, null, page++, 1000);
            for (GlossaryTerm candidate : batch.items()) {
                if (candidate.status() == GlossaryTermStatus.REJECTED) continue;
                String key = candidate.key();
                if (!key.matches("[A-Z]+") || Math.abs(key.length() - term.length()) > 2) continue;
                int distance = editDistance(term, key);
                int maxDistance = Math.min(term.length(), key.length()) <= 5 ? 1 : 2;
                if (distance > 0 && distance <= maxDistance) {
                    matches.add(new Suggestion(candidate.id(), key, candidate.persian(),
                            candidate.status(), distance));
                }
            }
        } while (!batch.items().isEmpty());
        matches.sort(Comparator.comparingInt(Suggestion::distance)
                .thenComparingInt(v -> v.status() == GlossaryTermStatus.APPROVED ? 0 : 1)
                .thenComparing(Suggestion::key));
        List<Suggestion> selected = matches.stream().limit(limit).toList();
        return new Advice(term, selected.isEmpty() ? "NEW" : "POSSIBLE_TYPO",
                null, null, selected, false);
    }

    private static Advice pluralAdvice(String token, String objectType, String singular,
                                       GlossaryLexicon lexicon) {
        GlossaryTermStatus canonicalStatus = lexicon.atomic().contains(singular)
                ? GlossaryTermStatus.APPROVED
                : lexicon.candidateAtomic().contains(singular)
                        ? GlossaryTermStatus.CANDIDATE : GlossaryTermStatus.REJECTED;
        String classification = "COLUMN".equals(objectType) ? "PLURAL_COLUMN_VIOLATION"
                : switch (canonicalStatus) {
                    case APPROVED -> "KNOWN_PLURAL";
                    case CANDIDATE -> "PENDING_APPROVAL";
                    case REJECTED -> "REJECTED";
                };
        return new Advice(token, classification, singular, canonicalStatus, List.of(), false);
    }

    /** Optimal-string-alignment edit distance (single-character edits and adjacent transposition). */
    static int editDistance(String left, String right) {
        int[][] distance = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) distance[i][0] = i;
        for (int j = 0; j <= right.length(); j++) distance[0][j] = j;
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int closest = Math.min(distance[i - 1][j] + 1,
                        Math.min(distance[i][j - 1] + 1, distance[i - 1][j - 1] + cost));
                if (i > 1 && j > 1 && left.charAt(i - 1) == right.charAt(j - 2)
                        && left.charAt(i - 2) == right.charAt(j - 1)) {
                    closest = Math.min(closest, distance[i - 2][j - 2] + 1);
                }
                distance[i][j] = closest;
            }
        }
        return distance[left.length()][right.length()];
    }

    public record Suggestion(long id, String key, String persian,
                             GlossaryTermStatus status, int distance) { }

    public record Advice(String term, String classification, String canonicalSingular,
                         GlossaryTermStatus canonicalStatus, List<Suggestion> suggestions,
                         boolean automaticChanges) { }
}
