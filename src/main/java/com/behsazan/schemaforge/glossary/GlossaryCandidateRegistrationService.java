package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.specification.validation.spelling.OfflineHunspellSpellCheckService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One governance pass per generation (never per DBMS). No network calls, no
 * changes to canonical model/DDL, no automatic approval. Distinguishes a
 * dictionary-confirmed NEW term from a mere lack of spelling suggestions.
 */
public final class GlossaryCandidateRegistrationService {
    private final GlossaryCandidateRegistrationMode mode;
    private final GlossaryReviewService reviewService;
    private final OfflineHunspellSpellCheckService offline;

    public GlossaryCandidateRegistrationService(GlossaryCandidateRegistrationMode mode,
                                                GlossaryReviewService reviewService,
                                                SpellCheckProperties spellingProperties) {
        this.mode = Objects.requireNonNull(mode, "registration mode");
        this.reviewService = Objects.requireNonNull(reviewService, "review service");
        Objects.requireNonNull(spellingProperties, "spelling properties");
        // Candidate safety must not be bypassed when normal spelling warnings
        // are disabled or the user explicitly chooses the network provider.
        SpellCheckProperties local = new SpellCheckProperties();
        local.setEnabled(true);
        local.setFailOpen(false);
        local.setMaximumSuggestions(Math.max(1, spellingProperties.getMaximumSuggestions()));
        local.setTechnicalTerms(spellingProperties.getTechnicalTerms());
        this.offline = new OfflineHunspellSpellCheckService(local);
    }

    public GlossaryCandidateRegistrationMode mode() { return mode; }

    /** Report contains evidence only in DRY_RUN; AUTO_CANDIDATE writes one H2 transaction. */
    public Result process(List<UnknownGlossaryTerm> findings, GlossaryLexicon lexicon,
                          String reportSha256, String generationId) {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(lexicon, "glossary snapshot");
        if (mode == GlossaryCandidateRegistrationMode.OFF) {
            return new Result(mode.name(), 0, 0, 0, 0, 0, 0, 0,
                    List.of(), List.of(), List.of());
        }
        TreeSet<String> newWords = new TreeSet<>();
        for (UnknownGlossaryTerm finding : findings) {
            if ("NEW".equals(finding.reviewStatus())) newWords.add(finding.term());
        }
        Set<String> known = new HashSet<>();
        known.addAll(lexicon.atomic());
        known.addAll(lexicon.candidateAtomic());
        known.addAll(lexicon.rejectedAtomic());
        Map<Integer, List<String>> knownByLength = new HashMap<>();
        for (String candidate : known) {
            knownByLength.computeIfAbsent(candidate.length(), ignored -> new ArrayList<>()).add(candidate);
        }
        Set<String> eligible = new LinkedHashSet<>();
        Set<String> typos = new LinkedHashSet<>();
        Set<String> review = new LinkedHashSet<>();
        for (String word : newWords) {
            if (!word.matches("[A-Z]{4,32}") || GlossaryInflection.knownSingular(word, lexicon) != null) {
                review.add(word);
                continue;
            }
            // A plural is not a distinct atomic canonical term even when its
            // singular is not YET present in H2. Route it for manual singular
            // review instead of importing SCHOOLS without SCHOOL.
            if (GlossaryInflection.singularCandidates(word).stream()
                    .anyMatch(offline::isKnownEnglishWord)) {
                review.add(word);
                continue;
            }
            // Positive evidence is required; distant typos can be suggestion-free.
            if (offline.isKnownEnglishWord(word)) {
                eligible.add(word);
            } else if (hasOfflineSuggestion(word) || closeToKnownWord(word, knownByLength)) {
                typos.add(word);
            } else {
                // Real bank jargon/acronyms require steward review rather than
                // treating a dictionary's silence as proof that spelling is correct.
                review.add(word);
            }
        }
        int inserted = 0;
        int alreadyKnown = 0;
        int recordedOccurrences = 0;
        if (mode == GlossaryCandidateRegistrationMode.AUTO_CANDIDATE && !eligible.isEmpty()) {
            var insertedReport = reviewService.registerAutoCandidates(
                    findings, eligible, reportSha256, generationId);
            inserted = insertedReport.inserted();
            alreadyKnown = insertedReport.alreadyKnown();
            recordedOccurrences = insertedReport.recordedOccurrences();
        }
        return new Result(mode.name(), newWords.size(), eligible.size(), typos.size(), review.size(),
                inserted, alreadyKnown, recordedOccurrences,
                List.copyOf(eligible), List.copyOf(typos), List.copyOf(review));
    }

    private boolean hasOfflineSuggestion(String word) {
        return offline.check(word).stream().anyMatch(
                error -> !error.serviceFailure() && !error.suggestions().isEmpty());
    }

    private static boolean closeToKnownWord(String word, Map<Integer, List<String>> knownByLength) {
        int maxDistance = word.length() <= 5 ? 1 : 2;
        for (int len = word.length() - maxDistance; len <= word.length() + maxDistance; len++) {
            for (String candidate : knownByLength.getOrDefault(len, List.of())) {
                if (!candidate.matches("[A-Z]+")) continue;
                if (GlossarySpellingAdvisor.editDistance(word, candidate) <= maxDistance) return true;
            }
        }
        return false;
    }

    /** Stable, user-visible subset, suitable for inclusion in glossary summary.json. */
    public record Result(String mode, int uniqueNewTerms, int eligibleCandidates,
                         int possibleTypos, int requiresManualReview,
                         int insertedCandidates, int alreadyKnown, int evidenceOccurrencesRecorded,
                         List<String> eligibleTerms, List<String> suspectedTypos, List<String> manualReviewTerms) { }
}
