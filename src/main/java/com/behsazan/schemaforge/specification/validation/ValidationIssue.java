package com.behsazan.schemaforge.specification.validation;

public record ValidationIssue(String severity, String code, String path, String message) { }
