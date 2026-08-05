package com.behsazan.schemaforge.specification.parser.legacy;

/** One warning, information item or error emitted while parsing a document. */
record ParserIssue(
        ParserIssueSeverity severity,
        String code,
        String fieldName,
        Integer sourceRowNumber,
        String message,
        String rawValue
) {
}
