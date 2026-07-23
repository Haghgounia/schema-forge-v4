package com.behsazan.schemaforge.domain.valueobject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Identifier(String value) implements Comparable<Identifier> {
    private static final Pattern VALID = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");

    public Identifier {
        value = Objects.requireNonNull(value, "identifier value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("identifier value must not be blank");
        if (!VALID.matcher(value).matches()) throw new IllegalArgumentException("invalid identifier: " + value);
    }

    public static Identifier of(String value) { return new Identifier(value); }
    public String normalized() { return value.toUpperCase(Locale.ROOT); }
    @Override public int compareTo(Identifier other) { return normalized().compareTo(other.normalized()); }
    @Override public String toString() { return value; }
}
