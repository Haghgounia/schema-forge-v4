package com.behsazan.schemaforge.glossary;

import java.util.Locale;

/** Explicit opt-in for writing newly discovered, spelling-screened glossary terms. */
public enum GlossaryCandidateRegistrationMode {
    OFF, DRY_RUN, AUTO_CANDIDATE;

    public static GlossaryCandidateRegistrationMode parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Glossary candidate-registration mode is required");
        }
        try {
            return valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported glossary candidate-registration mode: " + text
                    + " (supported: OFF, DRY_RUN, AUTO_CANDIDATE)", exception);
        }
    }
}
