package com.behsazan.schemaforge.specification.parser.legacy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Defines the immutable intermediate data model used by the legacy Word extraction pipeline.
 *
 * <p>The nested enums and records capture source-format detection, extracted table metadata,
 * column evidence, warnings, per-file outcomes and run-level statistics. These types retain
 * raw source values alongside normalized values so that downstream mapping and audit reports
 * can explain every recovery decision. They are deliberately package-private and are not the
 * canonical database domain model.</p>
 */
final class ExtractionModels {
    private ExtractionModels() {
    }

    /** Classifies the outcome of processing one source document. */
    enum Status {
        SUCCESS,
        PARTIAL,
        FAILED,
        IGNORED
    }

    /** Defines the audit severity assigned to an extraction finding. */
    enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** Identifies the detected physical Microsoft Word container format. */
    enum WordFormat {
        DOC,
        DOCX,
        UNKNOWN
    }

    /**
     * Captures normalized table-level metadata and the evidence source selected for the
     * Persian table title.
     */
    record Metadata(
            String documentType,
            String systemName,
            String tableName,
            String persianTableName,
            String persianTableNameSource,
            String entityName,
            String createdDateRaw,
            String modifiedDateRaw,
            String headerRaw
    ) {
    }

    /** Records a recoverable extraction issue without discarding the source document. */
    record ExtractionWarning(
            Severity severity,
            String code,
            String fieldName,
            Integer rowNumber,
            String message,
            String rawValue
    ) {
    }

    /**
     * Represents one extracted legacy field row, including raw cells, normalized names and
     * physical database hints that are consumed by the canonical mapping stage.
     */
    record ColumnDefinition(
            int sequence,
            int sourceTableIndex,
            int sourceRowIndex,
            String persianTitle,
            String fieldName,
            String fieldNameRaw,
            String typeRaw,
            String lengthRaw,
            String keyRaw,
            String indexRaw,
            String mandatoryRaw,
            Boolean mandatory,
            String db2TypeRaw,
            String db2LengthRaw,
            String referenceOrDefaultRaw,
            List<String> keys,
            List<String> indexes,
            List<String> rawCells
    ) {
    }

    /**
     * Aggregates the complete outcome and audit evidence for one Word document.
     */
    record FileResult(
            Path sourceFile,
            String relativePath,
            WordFormat declaredFormat,
            WordFormat sourceFormat,
            boolean formatMismatch,
            long fileSize,
            long durationMillis,
            Status status,
            Metadata metadata,
            List<ColumnDefinition> columns,
            List<ExtractionWarning> warnings,
            String rawMainText,
            String rawHeaderText,
            String errorClass,
            String errorMessage,
            String stackTrace,
            Instant processedAt
    ) {
        static FileResult ignored(Path sourceFile,
                                  String relativePath,
                                  long fileSize,
                                  long durationMillis,
                                  WordFormat declaredFormat,
                                  WordFormat sourceFormat) {
            return new FileResult(
                    sourceFile,
                    relativePath,
                    declaredFormat,
                    sourceFormat,
                    formatsMismatch(declaredFormat, sourceFormat),
                    fileSize,
                    durationMillis,
                    Status.IGNORED,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.now()
            );
        }

        static FileResult failed(Path sourceFile,
                                 String relativePath,
                                 WordFormat declaredFormat,
                                 WordFormat sourceFormat,
                                 long fileSize,
                                 long durationMillis,
                                 Throwable error,
                                 String stackTrace) {
            return new FileResult(
                    sourceFile,
                    relativePath,
                    declaredFormat,
                    sourceFormat,
                    formatsMismatch(declaredFormat, sourceFormat),
                    fileSize,
                    durationMillis,
                    Status.FAILED,
                    null,
                    List.of(),
                    List.of(new ExtractionWarning(
                            Severity.ERROR,
                            "FILE_PROCESSING_FAILED",
                            null,
                            null,
                            error.getMessage() == null ? error.toString() : error.getMessage(),
                            null
                    )),
                    null,
                    null,
                    error.getClass().getName(),
                    error.getMessage(),
                    stackTrace,
                    Instant.now()
            );
        }

        private static boolean formatsMismatch(WordFormat declaredFormat, WordFormat sourceFormat) {
            return declaredFormat != WordFormat.UNKNOWN
                    && sourceFormat != WordFormat.UNKNOWN
                    && declaredFormat != sourceFormat;
        }
    }

    /** Summarizes throughput, outcomes, warnings and memory usage for a parser run. */
    record RunSummary(
            String runName,
            Path inputDirectory,
            Path runDirectory,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            int configuredThreads,
            long scannedFiles,
            long reportedDocuments,
            long tableDocuments,
            long successFiles,
            long partialFiles,
            long failedFiles,
            long totalColumns,
            long totalWarnings,
            double scannedFilesPerSecond,
            double acceptedTableDocumentsPerSecond,
            double documentsPerSecond,
            long usedHeapBytes,
            long maxHeapBytes,
            Map<String, Long> formatCounts,
            Map<String, Long> warningCounts
    ) {
    }
}
