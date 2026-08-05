package com.behsazan.schemaforge.specification.parser.legacy;

/** Confidence attached to logical and physical type values. */
enum DataTypeConfidence {
    TRUSTED,
    NOT_PRESENT,
    INVALID_SOURCE_TOKEN,
    UNRELIABLE
}
