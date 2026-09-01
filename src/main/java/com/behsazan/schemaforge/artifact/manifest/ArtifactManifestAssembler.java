package com.behsazan.schemaforge.artifact.manifest;

import com.behsazan.schemaforge.artifact.ArtifactContract;
import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds and validates Standard Artifact Manifest V1 from a final C5 package directory. */
public final class ArtifactManifestAssembler {

    public ArtifactManifest assemble(
            Path artifactRoot,
            ArtifactGenerationContext context,
            List<ModelInput> modelInputs,
            Map<String, Object> extensions) throws IOException {
        Objects.requireNonNull(artifactRoot, "artifactRoot must not be null");
        Objects.requireNonNull(context, "context must not be null");
        List<ModelInput> inputs = modelInputs == null ? List.of() : List.copyOf(modelInputs);
        List<ArtifactDescriptor> descriptors = context.ledger().snapshot();

        validateDescriptors(context, descriptors);
        validatePreManifestFiles(artifactRoot, descriptors);

        List<ArtifactManifestModel> models = inputs.stream()
                .map(ArtifactManifestAssembler::toModel)
                .sorted(Comparator.comparing(ArtifactManifestModel::sourceName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ArtifactManifestModel::schema, String.CASE_INSENSITIVE_ORDER))
                .toList();
        ArtifactManifestValidation validation = validation(inputs);
        ArtifactManifestOutcomes outcomes = outcomes(descriptors);
        List<ArtifactManifestArtifact> artifacts = new ArrayList<>(descriptors.size());
        for (ArtifactDescriptor descriptor : descriptors) {
            artifacts.add(toArtifact(artifactRoot, descriptor));
        }
        artifacts.sort(artifactComparator());

        return new ArtifactManifest(
                ArtifactManifest.CONTRACT,
                ArtifactContract.VERSION,
                new ArtifactManifest.Generation(
                        context.generationId(),
                        context.generationTimestamp(),
                        context.generatedAt().toString()),
                new ArtifactManifest.Source(context.origin(), context.sourceName()),
                models,
                validation,
                outcomes,
                artifacts,
                extensions);
    }

    public void validateFinalPackage(Path artifactRoot, ArtifactGenerationContext context) throws IOException {
        Set<String> files = regularFilePaths(artifactRoot);
        Set<String> generated = new LinkedHashSet<>();
        for (ArtifactDescriptor descriptor : context.ledger().snapshot()) {
            if (descriptor.status() == ArtifactStatus.GENERATED) {
                generated.add(descriptor.relativePath());
            }
        }
        if (!files.equals(generated)) {
            throw new IllegalStateException(
                    "Manifest/package mismatch. files=" + files + ", generatedDescriptors=" + generated);
        }
    }

    private static ArtifactManifestModel toModel(ModelInput input) {
        List<String> tables = input.schema().tables().stream()
                .map(table -> table.qualifiedName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new ArtifactManifestModel(input.sourceName(), input.schema().name().value(), tables);
    }

    private static ArtifactManifestValidation validation(List<ModelInput> inputs) {
        if (inputs.isEmpty()) {
            return new ArtifactManifestValidation(false, 0, 0, 0);
        }
        long errors = 0;
        long warnings = 0;
        long recoveryWarnings = 0;
        for (ModelInput input : inputs) {
            for (ValidationIssue issue : input.validationReport().issues()) {
                if ("ERROR".equalsIgnoreCase(issue.severity())) {
                    errors++;
                } else if ("WARNING".equalsIgnoreCase(issue.severity())) {
                    warnings++;
                }
            }
            recoveryWarnings += recoveryWarningCount(input.schema());
        }
        return new ArtifactManifestValidation(true, errors, warnings, recoveryWarnings);
    }

    private static long recoveryWarningCount(DatabaseSchema schema) {
        String value = schema.metadata().get("recovery.warningCount");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static ArtifactManifestOutcomes outcomes(List<ArtifactDescriptor> descriptors) {
        long generated = descriptors.stream().filter(d -> d.status() == ArtifactStatus.GENERATED).count();
        long skipped = descriptors.stream().filter(d -> d.status() == ArtifactStatus.SKIPPED).count();
        long failed = descriptors.stream().filter(d -> d.status() == ArtifactStatus.FAILED).count();
        return new ArtifactManifestOutcomes(generated, skipped, failed);
    }

    private static ArtifactManifestArtifact toArtifact(Path artifactRoot, ArtifactDescriptor descriptor)
            throws IOException {
        ArtifactIntegrity integrity = null;
        if (descriptor.status() == ArtifactStatus.GENERATED && descriptor.type() != ArtifactType.MANIFEST) {
            Path file = resolveGeneratedFile(artifactRoot, descriptor.relativePath());
            integrity = new ArtifactIntegrity("SHA-256", sha256(file), Files.size(file));
        }
        return new ArtifactManifestArtifact(
                descriptor.type(),
                descriptor.platform(),
                descriptor.logicalName(),
                blankToNull(descriptor.relativePath()),
                blankToNull(descriptor.mediaType()),
                descriptor.status(),
                blankToNull(descriptor.outcomeReason()),
                new ArtifactManifestArtifact.Provenance(
                        descriptor.provenance().origin(),
                        descriptor.provenance().sourceName(),
                        descriptor.provenance().producer()),
                integrity);
    }

    private static Comparator<ArtifactManifestArtifact> artifactComparator() {
        return Comparator.comparing((ArtifactManifestArtifact a) -> a.type().name())
                .thenComparing(a -> a.platform() == null ? "" : a.platform().name())
                .thenComparing(a -> a.logicalName().toLowerCase(Locale.ROOT))
                .thenComparing(a -> a.status().name())
                .thenComparing(a -> a.path() == null ? "" : a.path().toLowerCase(Locale.ROOT));
    }

    private static void validateDescriptors(
            ArtifactGenerationContext context, List<ArtifactDescriptor> descriptors) {
        long manifestCount = descriptors.stream()
                .filter(d -> d.type() == ArtifactType.MANIFEST && d.status() == ArtifactStatus.GENERATED)
                .count();
        if (manifestCount != 1) {
            throw new IllegalStateException("Exactly one generated manifest descriptor is required: " + manifestCount);
        }
        Set<String> generatedPaths = new HashSet<>();
        for (ArtifactDescriptor descriptor : descriptors) {
            if (!context.generationId().equals(descriptor.generationId())) {
                throw new IllegalStateException("Artifact generationId differs from manifest generationId");
            }
            if (descriptor.status() == ArtifactStatus.GENERATED) {
                String key = descriptor.relativePath().toLowerCase(Locale.ROOT);
                if (!generatedPaths.add(key)) {
                    throw new IllegalStateException("Duplicate generated artifact path: " + descriptor.relativePath());
                }
            }
        }
    }

    private static void validatePreManifestFiles(Path artifactRoot, List<ArtifactDescriptor> descriptors)
            throws IOException {
        Set<String> files = regularFilePaths(artifactRoot);
        Set<String> expected = new LinkedHashSet<>();
        for (ArtifactDescriptor descriptor : descriptors) {
            if (descriptor.status() == ArtifactStatus.GENERATED && descriptor.type() != ArtifactType.MANIFEST) {
                expected.add(descriptor.relativePath());
            }
        }
        if (!files.equals(expected)) {
            throw new IllegalStateException(
                    "Untracked or missing generated artifacts before manifest write. files=" + files
                            + ", expected=" + expected);
        }
    }

    private static Set<String> regularFilePaths(Path artifactRoot) throws IOException {
        Set<String> files = new LinkedHashSet<>();
        try (var paths = Files.walk(artifactRoot)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> artifactRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(files::add);
        }
        return files;
    }

    private static Path resolveGeneratedFile(Path artifactRoot, String relativePath) {
        Path root = artifactRoot.toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("Generated artifact is missing from package: " + relativePath);
        }
        return file;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ModelInput(
            String sourceName,
            DatabaseSchema schema,
            ValidationReport validationReport) {
        public ModelInput {
            sourceName = sourceName == null ? "" : sourceName.trim();
            Objects.requireNonNull(schema, "schema must not be null");
            Objects.requireNonNull(validationReport, "validationReport must not be null");
        }
    }
}
