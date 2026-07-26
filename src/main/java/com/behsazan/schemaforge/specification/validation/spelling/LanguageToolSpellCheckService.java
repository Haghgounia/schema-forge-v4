package com.behsazan.schemaforge.specification.validation.spelling;

import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional LanguageTool client. Identifiers are never modified. */
public final class LanguageToolSpellCheckService implements SpellCheckService {
    private final SpellCheckProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Set<String> technicalTerms;
    private final AtomicBoolean serviceFailureReported = new AtomicBoolean(false);

    public LanguageToolSpellCheckService(SpellCheckProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.technicalTerms = normalizeTerms(properties.getTechnicalTerms());
    }

    @Override
    public List<SpellingError> check(String text) {
        if (!properties.isEnabled() || text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = normalizeIdentifier(text);
        if (normalized.isBlank() || allWordsAreTechnical(normalized)) {
            return List.of();
        }

        try {
            String body = "language=" + encode(properties.getLanguage())
                    + "&text=" + encode(normalized);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(properties.getRequestTimeout())
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", "SchemaForge/4.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure("LanguageTool returned HTTP " + response.statusCode());
            }
            return parse(normalized, response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure("Spell-check request was interrupted");
        } catch (IOException | RuntimeException ex) {
            return failure("Spell-check service unavailable: " + safeMessage(ex));
        }
    }

    static String normalizeIdentifier(String text) {
        return text
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private List<SpellingError> parse(String text, String json) throws IOException {
        JsonNode matches = objectMapper.readTree(json).path("matches");
        if (!matches.isArray()) {
            return List.of();
        }

        List<SpellingError> errors = new ArrayList<>();
        for (JsonNode match : matches) {
            String issueType = match.path("rule").path("issueType").asText("");
            String category = match.path("rule").path("category").path("id").asText("");
            if (!"misspelling".equalsIgnoreCase(issueType)
                    && !"TYPOS".equalsIgnoreCase(category)) {
                continue;
            }

            String word = extract(text, match.path("offset").asInt(-1), match.path("length").asInt(0));
            if (word.isBlank() || technicalTerms.contains(normalizeWord(word))) {
                continue;
            }

            List<SpellingSuggestion> suggestions = new ArrayList<>();
            boolean capitalizationOnly = false;

            for (JsonNode replacement : match.path("replacements")) {
                String value = replacement.path("value")
                        .asText("")
                        .trim();

                if (value.isBlank()) {
                    continue;
                }

                if (value.equalsIgnoreCase(word)
                        && !value.equals(word)) {
                    capitalizationOnly = true;
                }

                if (suggestions.size()
                        < Math.max(
                        0,
                        properties.getMaximumSuggestions())) {

                    suggestions.add(
                            new SpellingSuggestion(value));
                }
            }

            if (capitalizationOnly) {
                continue;
            }

            errors.add(new SpellingError(
                    word.toUpperCase(Locale.ROOT),
                    match.path("message")
                            .asText("Possible spelling error"),
                    suggestions));
        }
        return List.copyOf(errors);
    }

    private boolean allWordsAreTechnical(String text) {
        for (String word : text.split("\\s+")) {
            if (!technicalTerms.contains(normalizeWord(word))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> normalizeTerms(List<String> terms) {
        Set<String> result = new LinkedHashSet<>();
        if (terms != null) {
            for (String term : terms) {
                if (term != null && !term.isBlank()) {
                    result.add(normalizeWord(term));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String normalizeWord(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String extract(String text, int offset, int length) {
        if (offset < 0 || length <= 0 || offset >= text.length()) {
            return "";
        }
        return text.substring(offset, Math.min(text.length(), offset + length));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<SpellingError> failure(String message) {
        if (!properties.isFailOpen()) {
            throw new IllegalStateException(message);
        }
        if (serviceFailureReported.compareAndSet(false, true)) {
            return List.of(SpellingError.serviceFailure(message));
        }
        return List.of();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
