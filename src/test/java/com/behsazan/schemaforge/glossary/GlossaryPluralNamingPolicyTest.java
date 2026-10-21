package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryPluralNamingPolicyTest {
    @Test
    void aKnownSingularPreventsNewPluralButPluralInAColumnIsANamingViolation() {
        var glossary = new GlossaryLexicon(
                Set.of("PROFILE", "CITY", "BUSINESS", "STATUS", "CLASS", "ID"), Set.of(),
                Set.of("DEPOSIT"), Set.of(), Set.of("BLOCK"), Set.of());
        var schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "PROFILES")
                        .addColumn(Column.required("PROFILE_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("PROFILES_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("CITIES_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("BUSINESSES_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("STATUS_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("CLASSES_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("DEPOSITS_ID", DataType.simple("INTEGER")))
                        .build())
                .addTable(Table.builder("FIN", "DEPOSITS")
                        .addColumn(Column.required("BLOCKS_ID", DataType.simple("INTEGER"))).build())
                .build();
        var findings = new GlossaryScanner().scan("source.docx", schema, glossary);
        assertFalse(findings.stream().anyMatch(f -> "NEW".equals(f.reviewStatus())
                && List.of("PROFILES", "CITIES", "BUSINESSES", "CLASSES", "DEPOSITS", "BLOCKS")
                .contains(f.term())));
        assertFalse(findings.stream().anyMatch(f -> f.objectType().equals("TABLE")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("PROFILES")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("CITIES")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("BUSINESSES")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("CLASSES")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("DEPOSIT")
                && f.reviewStatus().equals("PENDING_APPROVAL")));
        assertTrue(findings.stream().anyMatch(f -> f.term().equals("BLOCK")
                && f.reviewStatus().equals("REJECTED")));
        assertFalse(findings.stream().anyMatch(f -> f.term().equals("STATUS")
                && f.reviewStatus().equals("PLURAL_COLUMN")));
    }

    @Test
    void ddlShowsNonExecutablePluralViolationAndReportImportIgnoresIt() {
        var glossary = new GlossaryLexicon(Set.of("PROFILE", "ID"), Set.of());
        var hints = new GlossaryDdlHints(glossary);
        assertEquals("", hints.beforeColumn("PROFILE_ID"));
        assertEquals("/* GLOSSARY_HINT: PLURAL_COLUMN=PROFILES */ ",
                hints.beforeColumn("PROFILES_ID"));
        assertEquals("/* GLOSSARY_HINT: MISSING=ALIEN | PLURAL_COLUMN=PROFILES */ ",
                hints.beforeColumn("ALIEN_PROFILES_ID"));
        String csv = GlossaryCsv.row("source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status")
                + GlossaryCsv.row("a.docx", "COLUMN", "PROFILES", "PROFILES_ID",
                "PROFILES_ID", "PROFILES", "1", "PLURAL_COLUMN");
        var parsed = GlossaryReportCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, parsed.newOccurrences().size());
        assertEquals(1, parsed.skippedNonNew());
    }

    @Test
    void ambiguousSuffixesAreNotGuessedWithoutAnExistingSingular() {
        assertEquals(List.of(), GlossaryInflection.singularCandidates("STATUS"));
        assertEquals(List.of(), GlossaryInflection.singularCandidates("BUSINESS"));
        assertEquals(List.of("PROFILE"), GlossaryInflection.singularCandidates("PROFILES"));
        assertEquals(List.of("CITY"), GlossaryInflection.singularCandidates("CITIES"));
        assertTrue(GlossaryInflection.singularCandidates("CLASSES").contains("CLASS"));
        assertTrue(GlossaryInflection.singularCandidates("HOUSES").contains("HOUSE"));
    }
}
