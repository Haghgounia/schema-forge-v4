package com.behsazan.schemaforge.glossary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Conservative English plural candidates; a form is recognized only if its singular is in H2. */
public final class GlossaryInflection {
    private GlossaryInflection() { }

    public static List<String> singularCandidates(String token) {
        if (token == null || token.length() < 4 || !token.matches("[A-Z]+")
                || Set.of("NEWS", "SERIES", "SPECIES", "PHYSICS", "MATHEMATICS").contains(token)) {
            return List.of();
        }
        Set<String> forms = new LinkedHashSet<>();
        if (token.endsWith("IES") && token.length() > 4) {
            forms.add(token.substring(0, token.length() - 3) + "Y");
        }
        if (token.endsWith("ES") && token.length() > 4) {
            String withoutEs = token.substring(0, token.length() - 2);
            if (withoutEs.endsWith("S") || withoutEs.endsWith("X") || withoutEs.endsWith("Z")
                    || withoutEs.endsWith("CH") || withoutEs.endsWith("SH") || withoutEs.endsWith("O")) {
                forms.add(withoutEs);
            }
        }
        if (token.endsWith("S") && !token.endsWith("SS")
                && !token.endsWith("US") && !token.endsWith("IS") && !token.endsWith("IES")) {
            forms.add(token.substring(0, token.length() - 1));
        }
        forms.remove(token);
        return List.copyOf(new ArrayList<>(forms));
    }

    public static String knownSingular(String token, GlossaryLexicon lexicon) {
        for (String singular : singularCandidates(token)) {
            if (lexicon.atomic().contains(singular) || lexicon.candidateAtomic().contains(singular)
                    || lexicon.rejectedAtomic().contains(singular)) return singular;
        }
        return null;
    }
}
