package com.behsazan.schemaforge.specification.parser.legacy;

/** Immutable table metadata extracted from one Word document. */
record ParsedWordTable(
        String documentType,
        String systemName,
        String technicalName,
        String persianName,
        MetadataConfidence persianNameConfidence,
        String persianNameSource,
        String entityName,
        String createdDateRaw,
        String modifiedDateRaw
) {
}
