package com.behsazan.schemaforge.specification.validation.spelling;

import com.behsazan.schemaforge.config.SpellCheckProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Fast, network-free identifier spell check backed by the bundled en_US Hunspell
 * dictionary and its English prefix/suffix rules. It is a lexical advisory,
 * not a grammar checker and never approves a Glossary term or rewrites an identifier.
 *
 * <p>The dictionary is loaded and expanded once per JVM. Token-level results are
 * cached so 1,000 columns sharing words do not repeat similarity searches.</p>
 */
public final class OfflineHunspellSpellCheckService implements SpellCheckService {
    private static final String RESOURCE_BASE = "/spelling/";
    private static final int CACHE_CAPACITY = 8_192;
    private final SpellCheckProperties properties;
    private final Set<String> technicalTerms;
    private final Map<String, List<SpellingError>> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean loadFailureReported = new AtomicBoolean();

    public OfflineHunspellSpellCheckService(SpellCheckProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        Set<String> terms = new HashSet<>();
        for (String term : properties.getTechnicalTerms()) {
            if (term != null && !term.isBlank()) {
                terms.add(term.strip().toLowerCase(Locale.ROOT));
            }
        }
        this.technicalTerms = Set.copyOf(terms);
    }

    @Override
    public List<SpellingError> check(String text) {
        if (!properties.isEnabled() || text == null || text.isBlank()) return List.of();

        final Dictionary dictionary;
        try {
            dictionary = DictionaryHolder.DICTIONARY;
        } catch (ExceptionInInitializerError | NoClassDefFoundError exception) {
            if (!properties.isFailOpen()) {
                throw new IllegalStateException("Offline spelling dictionary is unavailable", exception);
            }
            if (loadFailureReported.compareAndSet(false, true)) {
                return List.of(SpellingError.serviceFailure("Offline spelling dictionary is unavailable"));
            }
            return List.of();
        }

        String normalized = IdentifierSpellTokenizer.normalizeIdentifier(text);
        if (normalized.isBlank()) return List.of();
        List<SpellingError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : normalized.split("\\s+")) {
            // IDs, acronyms, sequence numbers and source-specific codes are not
            // reliable English spelling evidence; governance handles them.
            if (token.length() < 4 || token.length() > 32 || !asciiLetters(token)
                    || technicalTerms.contains(token) || !seen.add(token)) continue;
            if (dictionary.words.contains(token)) continue;
            errors.addAll(cache.computeIfAbsent(token, word -> assess(word, dictionary)));
        }
        if (cache.size() > CACHE_CAPACITY) cache.clear();
        return List.copyOf(errors);
    }

    /**
     * Positive lexical evidence for governance: unlike {@link #check(String)},
     * false means UNKNOWN rather than misspelled. A distant misspelling can yield
     * no suggestions, so absence of a warning must never authorize auto-import.
     * Technical-term exclusions also are not proof of dictionary membership.
     * Fails closed if the embedded dictionary is unavailable.
     */
    public boolean isKnownEnglishWord(String word) {
        if (word == null || word.length() < 4 || word.length() > 32) return false;
        String token = word.strip().toLowerCase(Locale.ROOT);
        if (!asciiLetters(token)) return false;
        try {
            return DictionaryHolder.DICTIONARY.words.contains(token);
        } catch (ExceptionInInitializerError | NoClassDefFoundError exception) {
            throw new IllegalStateException("Offline spelling dictionary required for automatic registration", exception);
        }
    }

    private List<SpellingError> assess(String token, Dictionary dictionary) {
        int maxDistance = token.length() <= 5 ? 1 : 2;
        int suggestionLimit = Math.max(0, properties.getMaximumSuggestions());
        if (suggestionLimit == 0) return List.of();

        Set<String> candidates = new LinkedHashSet<>();
        collect(candidates, dictionary.prefix.get(first2(token)));
        collect(candidates, dictionary.suffix.get(last2(token)));
        // A misspelling near both ends is unusual, but adding an internal
        // trigram allows suggestions without a complete dictionary scan.
        if (token.length() >= 7) {
            collect(candidates, dictionary.trigrams.get(token.substring(2, 5)));
        }
        List<RankedWord> found = new ArrayList<>();
        for (String candidate : candidates) {
            if (Math.abs(token.length() - candidate.length()) > maxDistance) continue;
            int distance = distanceAtMost(token, candidate, maxDistance);
            if (distance <= maxDistance) found.add(new RankedWord(candidate, distance));
        }
        if (found.isEmpty()) return List.of();

        found.sort(Comparator.comparingInt(RankedWord::distance)
                // The curated Glossary is a ranking hint, NEVER an approval source.
                .thenComparingInt(value -> dictionary.glossaryPriority().contains(value.word()) ? 0 : 1)
                .thenComparingInt(value -> value.word().charAt(value.word().length() - 1) == token.charAt(token.length() - 1) ? 0 : 1)
                .thenComparingInt(value -> Math.abs(value.word().length() - token.length()))
                .thenComparing(RankedWord::word));
        List<SpellingSuggestion> suggestions = new ArrayList<>();
        for (RankedWord word : found) {
            if (suggestions.size() >= suggestionLimit) break;
            suggestions.add(new SpellingSuggestion(word.word().toUpperCase(Locale.ROOT)));
        }
        return List.of(new SpellingError(token.toUpperCase(Locale.ROOT),
                "Possible offline dictionary spelling error", suggestions));
    }

    private static void collect(Set<String> target, Collection<String> words) {
        if (words != null) target.addAll(words);
    }

    static int distanceAtMost(String a, String b, int maxDistance) {
        int m = a.length();
        int n = b.length();
        if (Math.abs(m - n) > maxDistance) return maxDistance + 1;
        int[] prev2 = new int[n + 1];
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int distance = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    distance = Math.min(distance, prev2[j - 2] + 1);
                }
                curr[j] = distance;
                rowMin = Math.min(rowMin, distance);
            }
            if (rowMin > maxDistance) return maxDistance + 1;
            int[] oldPrev2 = prev2;
            prev2 = prev;
            prev = curr;
            curr = oldPrev2;
        }
        return prev[n];
    }

    private static boolean asciiLetters(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) < 'a' || word.charAt(i) > 'z') return false;
        }
        return true;
    }

    private static String first2(String s) { return s.substring(0, Math.min(2, s.length())); }
    private static String last2(String s) { return s.substring(Math.max(0, s.length() - 2)); }
    private record RankedWord(String word, int distance) { }

    private static final class DictionaryHolder {
        private static final Dictionary DICTIONARY = Dictionary.load();
    }

    private record Dictionary(Set<String> words,
                              Set<String> glossaryPriority,
                              Map<String, List<String>> prefix,
                              Map<String, List<String>> suffix,
                              Map<String, List<String>> trigrams) {
        static Dictionary load() {
            try {
                Affixes affixes = Affixes.load();
                Set<String> words = new HashSet<>(250_000);
                Set<String> noSuggest = new HashSet<>();
                try (BufferedReader reader = reader("en_US.dic")) {
                    // First line is the Hunspell entry count, not a word.
                    String line = reader.readLine();
                    if (line == null || !line.strip().matches("\\d+")) {
                        throw new IOException("Hunspell dictionary entry count is missing");
                    }
                    while ((line = reader.readLine()) != null) {
                        String head = line.split("\\s", 2)[0];
                        int slash = head.lastIndexOf('/');
                        String stem = slash < 0 ? head : head.substring(0, slash);
                        String flags = slash < 0 ? "" : head.substring(slash + 1);
                        stem = stem.toLowerCase(Locale.ROOT);
                        if (!asciiLetters(stem)) continue;
                        if (stem.length() > 32) continue;
                        boolean suppressed = flags.indexOf('!') >= 0; // Hunspell NOSUGGEST
                        addWord(words, noSuggest, stem, suppressed);
                        List<AffixRule> matchedPrefixes = new ArrayList<>();
                        List<AffixRule> matchedSuffixes = new ArrayList<>();
                        for (int i = 0; i < flags.length(); i++) {
                            char flag = flags.charAt(i);
                            for (AffixRule prefix : affixes.prefix().getOrDefault(flag, List.of())) {
                                if (prefix.matches(stem)) matchedPrefixes.add(prefix);
                            }
                            for (AffixRule suffix : affixes.suffix().getOrDefault(flag, List.of())) {
                                if (suffix.matches(stem)) matchedSuffixes.add(suffix);
                            }
                        }
                        for (AffixRule prefix : matchedPrefixes) {
                            addWord(words, noSuggest, prefix.apply(stem), suppressed);
                        }
                        for (AffixRule suffix : matchedSuffixes) {
                            addWord(words, noSuggest, suffix.apply(stem), suppressed);
                        }
                        for (AffixRule prefix : matchedPrefixes) {
                            if (!prefix.crossProduct()) continue;
                            for (AffixRule suffix : matchedSuffixes) {
                                if (suffix.crossProduct()) {
                                    // English prefix/suffix rules are independent; both
                                    // conditions are evaluated against the unmodified stem.
                                    addWord(words, noSuggest, prefix.apply(suffix.apply(stem)), suppressed);
                                }
                            }
                        }
                    }
                }
                Map<String, List<String>> prefix = new HashMap<>();
                Map<String, List<String>> suffix = new HashMap<>();
                Map<String, List<String>> trigrams = new HashMap<>();
                for (String word : words) {
                    if (word.length() < 4 || word.length() > 32 || !asciiLetters(word)
                            || noSuggest.contains(word)) continue;
                    prefix.computeIfAbsent(first2(word), ignored -> new ArrayList<>()).add(word);
                    suffix.computeIfAbsent(last2(word), ignored -> new ArrayList<>()).add(word);
                    if (word.length() >= 5) {
                        String key = word.substring(2, 5);
                        trigrams.computeIfAbsent(key, ignored -> new ArrayList<>()).add(word);
                    }
                }
                Set<String> glossaryPriority = new HashSet<>();
                try (BufferedReader priorityReader = reader("glossary-priority-terms.txt")) {
                    String line;
                    while ((line = priorityReader.readLine()) != null) {
                        String word = line.strip().toLowerCase(Locale.ROOT);
                        if (!word.startsWith("#") && asciiLetters(word)) glossaryPriority.add(word);
                    }
                }
                return new Dictionary(Set.copyOf(words), Set.copyOf(glossaryPriority), prefix, suffix, trigrams);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load the bundled Hunspell dictionary", exception);
            }
        }
    }

    private static void addWord(Set<String> words, Set<String> noSuggest,
                                String word, boolean suppressed) {
        words.add(word);
        if (suppressed) noSuggest.add(word);
    }

    private record Affixes(Map<Character, List<AffixRule>> prefix,
                           Map<Character, List<AffixRule>> suffix) {
        static Affixes load() throws IOException {
            Map<Character, List<AffixRule>> prefixes = new HashMap<>();
            Map<Character, List<AffixRule>> suffixes = new HashMap<>();
            Map<String, Boolean> crossable = new HashMap<>();
            try (BufferedReader reader = reader("en_US.aff")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.strip();
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.split("\\s+");
                    if (fields.length < 4 || !(fields[0].equals("PFX") || fields[0].equals("SFX"))) continue;
                    if (fields.length == 4 && (fields[2].equals("Y") || fields[2].equals("N"))) {
                        crossable.put(fields[0] + fields[1], fields[2].equals("Y"));
                    } else if (fields.length >= 5 && fields[1].length() == 1) {
                        char flag = fields[1].charAt(0);
                        boolean isPrefix = fields[0].equals("PFX");
                        AffixRule rule = new AffixRule(isPrefix,
                                crossable.getOrDefault(fields[0] + fields[1], false),
                                fields[2].equals("0") ? "" : fields[2],
                                fields[3].equals("0") ? "" : fields[3],
                                Pattern.compile((isPrefix ? "^" : "") + fields[4] + (isPrefix ? "" : "$")));
                        (isPrefix ? prefixes : suffixes)
                                .computeIfAbsent(flag, ignored -> new ArrayList<>()).add(rule);
                    }
                }
            }
            return new Affixes(prefixes, suffixes);
        }
    }

    private record AffixRule(boolean prefix, boolean crossProduct, String strip,
                             String addition, Pattern condition) {
        boolean matches(String stem) {
            return condition.matcher(stem).find()
                    && (prefix ? stem.startsWith(strip) : stem.endsWith(strip));
        }
        String apply(String stem) {
            return prefix ? addition + stem.substring(strip.length())
                    : stem.substring(0, stem.length() - strip.length()) + addition;
        }
    }

    private static BufferedReader reader(String name) throws IOException {
        InputStream stream = OfflineHunspellSpellCheckService.class.getResourceAsStream(RESOURCE_BASE + name);
        if (stream == null) throw new IOException("Missing offline dictionary resource: " + name);
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
