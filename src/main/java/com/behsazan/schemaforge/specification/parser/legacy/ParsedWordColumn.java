package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.List;

/** Immutable field definition extracted from one Word table specification. */
record ParsedWordColumn(
        int sequence,
        int sourceTableIndex,
        int sourceRowIndex,
        String technicalName,
        String technicalNameRaw,
        String persianName,
        MetadataConfidence persianNameConfidence,
        String logicalTypeRaw,
        String logicalType,
        DataTypeConfidence logicalTypeConfidence,
        String lengthRaw,
        String normalizedLength,
        Integer length,
        Integer precision,
        Integer scale,
        boolean lengthAmbiguous,
        String keyRaw,
        List<String> keys,
        boolean primaryKey,
        boolean foreignKey,
        String indexRaw,
        List<String> indexes,
        Boolean mandatory,
        String physicalTypeRaw,
        String physicalType,
        DataTypeConfidence physicalTypeConfidence,
        String physicalLengthRaw,
        String normalizedPhysicalLength,
        String referencedTable,
        String defaultValue,
        String description,
        List<String> rawCells
) {
    public ParsedWordColumn {
        keys = keys == null ? List.of() : List.copyOf(keys);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        rawCells = rawCells == null ? List.of() : List.copyOf(rawCells);
    }
}
