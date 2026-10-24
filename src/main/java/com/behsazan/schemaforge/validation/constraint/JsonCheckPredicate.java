package com.behsazan.schemaforge.validation.constraint;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dialect-aware support for the simple SQL/JSON IS [NOT] JSON CHECK predicate. */
public final class JsonCheckPredicate {
    private static final Pattern SIMPLE = Pattern.compile(
            "(?i)^\\s*([A-Za-z][A-Za-z0-9_$#]*)\\s+IS\\s+(NOT\\s+)?JSON\\s*$");
    private static final Pattern ANY = Pattern.compile("(?i)\\bIS\\s+(?:NOT\\s+)?JSON\\b");

    private JsonCheckPredicate() { }

    public static boolean containsJsonPredicate(String expression) {
        return expression != null && ANY.matcher(expression).find();
    }

    /**
     * Returns the column of an affirmative, simple IS JSON constraint.
     * NOT JSON must not cause its column to be treated as JSON document storage.
     * The predicate is used when selecting Unicode-safe physical text storage.
     */
    public static Optional<String> affirmativeJsonColumn(String expression) {
        if (expression == null) return Optional.empty();
        Matcher matcher = SIMPLE.matcher(expression);
        return matcher.matches() && matcher.group(2) == null
                ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Empty when this dialect/profile cannot safely render the JSON validity constraint. */
    public static Optional<String> render(Dialect dialect, String expression) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        if (expression == null) return Optional.empty();
        Matcher matcher = SIMPLE.matcher(expression);
        if (!matcher.matches()) return Optional.empty();
        String column = dialect.quote(Identifier.of(matcher.group(1)));
        Optional<String> valid = dialect.jsonValidityPredicate(column);
        if (matcher.group(2) == null) return valid;
        return valid.map(predicate -> "NOT (" + predicate + ")");
    }
}
