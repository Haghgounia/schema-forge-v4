package com.behsazan.schemaforge.specification.parser.legacy;

import java.nio.file.Path;

/**
 * Public integration API for consuming the legacy Word parser from SchemaForge.
 *
 * <p>The API deliberately hides the batch/report implementation and all internal
 * extraction records. Callers receive immutable public DTOs and do not need to
 * parse CSV report files.</p>
 */
interface WordTableParser {

    /** Parses one DOC or DOCX file. The file parent is used as the relative-path root. */
    WordTableParseResult parse(Path document);

    /** Parses one DOC or DOCX file relative to the supplied source root. */
    WordTableParseResult parse(Path inputRoot, Path document);

    /** Creates the production parser with a 64 MiB per-file safety limit. */
    static WordTableParser create() {
        return new SchemaForgeWordTableParser(64L * 1024L * 1024L);
    }

    /** Creates the production parser with a caller-defined per-file safety limit. */
    static WordTableParser create(long maxFileBytes) {
        return new SchemaForgeWordTableParser(maxFileBytes);
    }
}
