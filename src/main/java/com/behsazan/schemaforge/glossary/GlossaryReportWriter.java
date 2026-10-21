package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes optional, nonblocking glossary findings before the manifest is assembled. */
public final class GlossaryReportWriter {
    private final GlossaryRepository repository;
    private final ObjectMapper mapper;
    private final GlossaryScanner scanner = new GlossaryScanner();

    public GlossaryReportWriter(GlossaryRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Captures the currently governed terms once for an individual generation request. */
    public GlossaryDdlHints ddlHints() {
        return new GlossaryDdlHints(repository.approvedLexicon());
    }

    public void write(Path output, ArtifactGenerationContext context,
                      List<ArtifactManifestAssembler.ModelInput> models) throws IOException {
        GlossaryLexicon snapshot = repository.approvedLexicon();
        List<UnknownGlossaryTerm> unknown = new ArrayList<>();
        int identifierCount = 0;
        for (var model : models) {
            unknown.addAll(scanner.scan(model.sourceName(), model.schema(), snapshot));
            identifierCount += model.schema().tables().size();
            identifierCount += model.schema().tables().stream().mapToInt(t -> t.columns().size()).sum();
        }
        Set<String> newTerms = new LinkedHashSet<>();
        Set<String> pendingTerms = new LinkedHashSet<>();
        Set<String> rejectedTerms = new LinkedHashSet<>();
        Set<String> pluralColumnTerms = new LinkedHashSet<>();
        int pluralColumnOccurrences = 0;
        for (UnknownGlossaryTerm term : unknown) {
            switch (term.reviewStatus()) {
                case "NEW" -> newTerms.add(term.term());
                case "PENDING_APPROVAL" -> pendingTerms.add(term.term());
                case "REJECTED" -> rejectedTerms.add(term.term());
                case "PLURAL_COLUMN" -> {
                    pluralColumnTerms.add(term.term());
                    pluralColumnOccurrences++;
                }
                default -> throw new IllegalStateException("Unexpected glossary review status");
            }
        }

        Path reportDir = Files.createDirectories(output.resolve("reports").resolve("glossary"));
        Path csv = reportDir.resolve("unknown-terms.csv");
        StringBuilder rows = new StringBuilder(GlossaryCsv.row(
                "source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status"));
        for (UnknownGlossaryTerm term : unknown) {
            rows.append(GlossaryCsv.row(term.sourceName(), term.objectType(), term.tableName(),
                    term.columnName(), term.identifier(), term.term(),
                    Integer.toString(term.tokenPosition()), term.reviewStatus()));
        }
        Files.writeString(csv, rows.toString(), StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.GLOSSARY_REPORT, null,
                "glossary-unknown-terms", ArtifactPaths.relative(output, csv),
                "text/csv", "GlossaryReportWriter");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contract", "schemaforge-glossary-report-v1");
        summary.put("generationId", context.generationId());
        summary.put("referenceWorksheet", "01_Atomic_Terms");
        summary.put("status", unknown.isEmpty() ? "CLEAN"
                : newTerms.isEmpty() ? "REVIEW_REQUIRED" : "NEW_TERMS_FOUND");
        summary.put("approvedLexiconSha256", lexiconFingerprint(snapshot));
        summary.put("checkedIdentifiers", identifierCount);
        summary.put("approvedAtomicTerms", snapshot.atomic().size());
        summary.put("approvedPhrases", snapshot.phrases().size());
        summary.put("reviewOccurrences", unknown.size());
        summary.put("uniqueNewTerms", newTerms.size());
        summary.put("uniquePendingTerms", pendingTerms.size());
        summary.put("uniqueRejectedTerms", rejectedTerms.size());
        summary.put("pluralColumnViolations", pluralColumnOccurrences);
        summary.put("uniquePluralColumnTerms", pluralColumnTerms.size());
        summary.put("newTerms", newTerms.stream().sorted().toList());
        summary.put("pendingTerms", pendingTerms.stream().sorted().toList());
        summary.put("rejectedTerms", rejectedTerms.stream().sorted().toList());
        summary.put("pluralColumnTerms", pluralColumnTerms.stream().sorted().toList());
        Path json = reportDir.resolve("summary.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), summary);
        context.ledger().generated(context, ArtifactType.GLOSSARY_REPORT, null,
                "glossary-summary", ArtifactPaths.relative(output, json),
                "application/json", "GlossaryReportWriter");
    }

    /** Stable identifier for the approved vocabulary actually used by this generation. */
    private static String lexiconFingerprint(GlossaryLexicon lexicon) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            lexicon.atomic().stream().sorted().forEach(term ->
                    digest.update(("A:" + term + "\n").getBytes(StandardCharsets.UTF_8)));
            lexicon.phrases().stream().sorted().forEach(term ->
                    digest.update(("P:" + term + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
