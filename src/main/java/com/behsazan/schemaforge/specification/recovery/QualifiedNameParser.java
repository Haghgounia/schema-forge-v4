package com.behsazan.schemaforge.specification.recovery;

import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses qualified database object names.
 */
public final class QualifiedNameParser {

    private final IdentifierSanitizer sanitizer =
            new IdentifierSanitizer();

    public QualifiedNameResult parse(
            String rawValue) {

        Objects.requireNonNull(rawValue);

        List<String> warnings =
                new ArrayList<>();

        String value =
                rawValue.trim()
                        .replace('\u00A0', ' ')
                        .replaceAll("\\s+", "")
                        .toUpperCase(Locale.ROOT);

        int dot =
                value.lastIndexOf('.');

        if (dot < 0) {

            RecoveryResult object =
                    sanitizer.sanitize(
                            value,
                            "object");

            warnings.addAll(object.warnings());

            return new QualifiedNameResult(
                    QualifiedName.of(
                            null,
                            object.value()),
                    warnings);
        }

        RecoveryResult schema =
                sanitizer.sanitize(
                        value.substring(0, dot),
                        "schema");

        RecoveryResult table =
                sanitizer.sanitize(
                        value.substring(dot + 1),
                        "object");

        warnings.addAll(schema.warnings());
        warnings.addAll(table.warnings());

        return new QualifiedNameResult(

                QualifiedName.of(
                        schema.value(),
                        table.value()),

                warnings);
    }
}
