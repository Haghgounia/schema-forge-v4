package com.behsazan.schemaforge.specification.validation;

/**
 * Provides validation issue functionality within the SchemaForge processing pipeline.
 *
 * @since 4.1
 */
public record ValidationIssue(String severity, String code, String path, String message) { }
