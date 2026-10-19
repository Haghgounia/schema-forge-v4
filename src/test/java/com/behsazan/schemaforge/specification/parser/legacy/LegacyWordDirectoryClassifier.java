package com.behsazan.schemaforge.specification.parser.legacy;

import java.nio.file.Path;
import java.util.List;

/**
 * Test-harness bridge for classifying legacy Word files before release-confidence batch generation.
 *
 * <p>The production legacy parser already distinguishes accepted table specifications from
 * documents that should be ignored. This bridge exposes only that classification to the
 * integration harness without changing the production parser API or guessing from filenames.</p>
 */
public final class LegacyWordDirectoryClassifier {
    private LegacyWordDirectoryClassifier() {
    }

    public static Classification classify(Path inputRoot, Path document) {
        try {
            WordTableParseResult result = WordTableParser.create().parse(inputRoot, document);
            List<String> issueCodes = result.issues().stream()
                    .map(ParserIssue::code)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .toList();

            if (result.acceptedTableDocument() && result.table() != null && !result.columns().isEmpty()) {
                return new Classification(
                        document,
                        result.relativePath(),
                        Outcome.TABLE_SPEC,
                        result.status().name(),
                        result.columns().size(),
                        issueCodes,
                        "",
                        "");
            }
            if (result.status() == WordTableParseStatus.IGNORED) {
                return new Classification(
                        document,
                        result.relativePath(),
                        Outcome.NOT_TABLE_SPEC,
                        result.status().name(),
                        0,
                        issueCodes,
                        "",
                        "");
            }
            if (result.status() == WordTableParseStatus.FAILED
                    && isSecurityPolicySkip(result.errorClass(), result.errorMessage())) {
                return new Classification(
                        document,
                        result.relativePath(),
                        Outcome.SECURITY_SKIPPED,
                        result.status().name(),
                        0,
                        issueCodes,
                        safe(result.errorClass()),
                        safe(result.errorMessage()));
            }
            return new Classification(
                    document,
                    result.relativePath(),
                    Outcome.CLASSIFICATION_FAILED,
                    result.status().name(),
                    result.columns().size(),
                    issueCodes,
                    safe(result.errorClass()),
                    safe(result.errorMessage()));
        } catch (RuntimeException failure) {
            return new Classification(
                    document,
                    relative(inputRoot, document),
                    Outcome.CLASSIFICATION_FAILED,
                    "EXCEPTION",
                    0,
                    List.of(),
                    failure.getClass().getName(),
                    safe(failure.getMessage()));
        }
    }

    static boolean isSecurityPolicySkip(String errorClass, String errorMessage) {
        String type = safe(errorClass);
        String message = safe(errorMessage);
        return "java.io.IOException".equals(type)
                && message.contains("Zip bomb detected!")
                && message.contains("MIN_INFLATE_RATIO");
    }

    private static String relative(Path inputRoot, Path document) {
        if (inputRoot == null || document == null) {
            return document == null ? "" : document.toString();
        }
        try {
            return inputRoot.toAbsolutePath().normalize()
                    .relativize(document.toAbsolutePath().normalize())
                    .toString();
        } catch (RuntimeException ignored) {
            return document.toString();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Outcome {
        TABLE_SPEC,
        NOT_TABLE_SPEC,
        SECURITY_SKIPPED,
        CLASSIFICATION_FAILED
    }

    public record Classification(
            Path document,
            String relativePath,
            Outcome outcome,
            String parserStatus,
            int columnCount,
            List<String> issueCodes,
            String errorClass,
            String errorMessage) {
        public Classification {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }
    }
}
