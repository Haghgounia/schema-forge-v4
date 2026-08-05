package com.behsazan.schemaforge.specification.parser.legacy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Public immutable result returned by {@link WordTableParser}. */
record WordTableParseResult(
        Path sourceFile,
        String relativePath,
        WordDocumentFormat declaredFormat,
        WordDocumentFormat detectedFormat,
        boolean formatMismatch,
        long fileSize,
        long durationMillis,
        WordTableParseStatus status,
        ParsedWordTable table,
        List<ParsedWordColumn> columns,
        List<ParserIssue> issues,
        String errorClass,
        String errorMessage,
        Instant processedAt
) {
    public WordTableParseResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean acceptedTableDocument() {
        return status == WordTableParseStatus.SUCCESS || status == WordTableParseStatus.PARTIAL;
    }
}
