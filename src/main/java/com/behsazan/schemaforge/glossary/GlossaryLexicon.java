package com.behsazan.schemaforge.glossary;

import java.util.Set;

/** One glossary snapshot; only APPROVED entries authorize a naming component. */
public record GlossaryLexicon(
        Set<String> atomic, Set<String> phrases,
        Set<String> candidateAtomic, Set<String> candidatePhrases,
        Set<String> rejectedAtomic, Set<String> rejectedPhrases) {
    public GlossaryLexicon {
        atomic = Set.copyOf(atomic);
        phrases = Set.copyOf(phrases);
        candidateAtomic = Set.copyOf(candidateAtomic);
        candidatePhrases = Set.copyOf(candidatePhrases);
        rejectedAtomic = Set.copyOf(rejectedAtomic);
        rejectedPhrases = Set.copyOf(rejectedPhrases);
    }

    /** Compatibility constructor for a vocabulary containing approved terms only. */
    public GlossaryLexicon(Set<String> atomic, Set<String> phrases) {
        this(atomic, phrases, Set.of(), Set.of(), Set.of(), Set.of());
    }
}
