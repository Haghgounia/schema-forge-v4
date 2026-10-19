package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** DBMS-independent, nonblocking vocabulary control on the prepared canonical model. */
public final class GlossaryScanner {
    public List<UnknownGlossaryTerm> scan(
            String sourceName, DatabaseSchema schema, GlossaryLexicon lexicon) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(lexicon, "lexicon");
        List<UnknownGlossaryTerm> result = new ArrayList<>();
        int maxPhraseLength = Stream.of(lexicon.phrases(), lexicon.candidatePhrases(), lexicon.rejectedPhrases())
                .flatMap(java.util.Collection::stream)
                .mapToInt(value -> (int) value.chars().filter(c -> c == '_').count() + 1)
                .max().orElse(1);
        for (Table table : schema.tables()) {
            String tableName = table.qualifiedName().name().value();
            scanIdentifier(sourceName, "TABLE", tableName, "", tableName, lexicon, maxPhraseLength, result);
            for (Column column : table.columns()) {
                scanIdentifier(sourceName, "COLUMN", tableName, column.name().value(),
                        column.name().value(), lexicon, maxPhraseLength, result);
            }
        }
        return List.copyOf(result);
    }

    private static void scanIdentifier(
            String source, String kind, String table, String column, String identifier,
            GlossaryLexicon lexicon, int maxPhraseLength, List<UnknownGlossaryTerm> result) {
        List<String> tokens = GlossaryNormalizer.identifierTokens(identifier);
        for (int i = 0; i < tokens.size();) {
            int matchedLength = 0;
            String status = null;
            String matchedTerm = null;
            for (int length = Math.min(maxPhraseLength, tokens.size() - i); length >= 2; length--) {
                String candidate = String.join("_", tokens.subList(i, i + length));
                if (lexicon.phrases().contains(candidate)) {
                    matchedLength = length;
                    break;
                } else if (lexicon.candidatePhrases().contains(candidate)) {
                    matchedLength = length;
                    status = "PENDING_APPROVAL";
                    matchedTerm = candidate;
                    break;
                } else if (lexicon.rejectedPhrases().contains(candidate)) {
                    matchedLength = length;
                    status = "REJECTED";
                    matchedTerm = candidate;
                    break;
                }
            }
            if (matchedLength > 0) {
                if (status != null) result.add(new UnknownGlossaryTerm(
                        source, kind, table, column, identifier, matchedTerm, i + 1, status));
                i += matchedLength;
            } else {
                String token = tokens.get(i);
                if (!lexicon.atomic().contains(token) && !lexicon.phrases().contains(token)) {
                    String tokenStatus = lexicon.candidateAtomic().contains(token)
                            || lexicon.candidatePhrases().contains(token) ? "PENDING_APPROVAL"
                            : lexicon.rejectedAtomic().contains(token) || lexicon.rejectedPhrases().contains(token)
                                    ? "REJECTED" : "NEW";
                    result.add(new UnknownGlossaryTerm(
                            source, kind, table, column, identifier, token, i + 1, tokenStatus));
                }
                i++;
            }
        }
    }
}
