package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlossaryScannerTest {
    @Test
    void normalizesSnakeCamelAbbreviationsAndSpaces() {
        assertEquals("ADVANCE_PAYMENT", GlossaryNormalizer.key("Advance Payment"));
        assertEquals(List.of("CUSTOMER", "ACCOUNT", "ID"),
                GlossaryNormalizer.identifierTokens("CustomerAccountID"));
        assertEquals(List.of("LOAN", "ORIGINATION", "FEE"),
                GlossaryNormalizer.identifierTokens("LOAN_ORIGINATION_FEE"));
        assertEquals(List.of("IBAN"), GlossaryNormalizer.identifierTokens("IBAN"));
        assertEquals(List.of("A", "B"), GlossaryNormalizer.identifierTokens("A__B_3"));
        assertThrows(IllegalArgumentException.class, () -> GlossaryNormalizer.key("  "));
    }

    @Test
    void longestApprovedPhraseSuppressesUnknownAtomicTokensAndUnknownsAreOccurrences() {
        DatabaseSchema schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "CUSTOMER_ACCOUNT")
                        .addColumn(Column.required("LOAN_ORIGINATION_FEE", DataType.simple("NUMBER")))
                        .addColumn(Column.required("UNKNOWN_CODE", DataType.simple("VARCHAR2")))
                        .addColumn(Column.required("CTFPRPSLTYP", DataType.simple("VARCHAR2")))
                        .build()).build();
        GlossaryLexicon lexicon = new GlossaryLexicon(
                Set.of("CUSTOMER", "ACCOUNT", "CODE"), Set.of("LOAN_ORIGINATION_FEE"));
        var found = new GlossaryScanner().scan("finance.docx", schema, lexicon);
        assertEquals(List.of("UNKNOWN", "CTFPRPSLTYP"), found.stream().map(UnknownGlossaryTerm::term).toList());
        assertEquals("COLUMN", found.get(0).objectType());
        assertEquals("UNKNOWN_CODE", found.get(0).columnName());
        assertEquals("finance.docx", found.get(0).sourceName());
    }

    @Test
    void candidateTermsAreNotTreatedAsApproved() {
        DatabaseSchema schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "DEPOSIT_ACCOUNT")
                        .addColumn(Column.required("NEW_FIELD", DataType.simple("NUMBER"))).build()).build();
        var found = new GlossaryScanner().scan("a.xml", schema,
                new GlossaryLexicon(Set.of("DEPOSIT", "ACCOUNT"), Set.of()));
        assertEquals(List.of("NEW", "FIELD"), found.stream().map(UnknownGlossaryTerm::term).toList());
    }
    @Test
    void candidateAndRejectedVocabularyAreNotMislabeledAsNew() {
        DatabaseSchema schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "LOAN_ORIGINATION_FEE")
                        .addColumn(Column.required("CUSTOMER_BLOCKED_UNKNOWN", DataType.simple("NUMBER")))
                        .build()).build();
        var glossary = new GlossaryLexicon(Set.of("CUSTOMER"), Set.of(),
                Set.of(), Set.of("LOAN_ORIGINATION_FEE"), Set.of("BLOCKED"), Set.of());
        var findings = new GlossaryScanner().scan("model.xml", schema, glossary);
        assertEquals(List.of("LOAN_ORIGINATION_FEE", "BLOCKED", "UNKNOWN"),
                findings.stream().map(UnknownGlossaryTerm::term).toList());
        assertEquals(List.of("PENDING_APPROVAL", "REJECTED", "NEW"),
                findings.stream().map(UnknownGlossaryTerm::reviewStatus).toList());
    }

}
