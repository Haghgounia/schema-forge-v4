package com.behsazan.schemaforge.specification.parser.legacy;

/** Overall result of parsing one Word document. */
enum WordTableParseStatus {
    SUCCESS,
    PARTIAL,
    FAILED,
    IGNORED
}
