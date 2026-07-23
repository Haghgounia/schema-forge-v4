package com.behsazan.schemaforge.domain.valueobject;

public record Description(String value) {
    public Description { value = value == null ? "" : value.trim(); }
    public static Description empty() { return new Description(""); }
    public boolean isEmpty() { return value.isEmpty(); }
}
