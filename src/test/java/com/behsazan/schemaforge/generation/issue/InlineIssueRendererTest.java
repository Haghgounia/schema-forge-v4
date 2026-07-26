package com.behsazan.schemaforge.generation.issue;

import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InlineIssueRendererTest {

    @Test
    void shouldRenderAllIssuesGroupedBySeverityWithoutDuplicates() {
        InlineIssueRenderer renderer = new InlineIssueRenderer();

        String comment = renderer.render(List.of(
                new ValidationIssue("WARNING", "SPELLING_WARNING", "p", "spelling"),
                new ValidationIssue("ERROR", "INVALID_DEFAULT_VALUE", "p", "default"),
                new ValidationIssue("WARNING", "METADATA_DATATYPE_MISMATCH", "p", "type"),
                new ValidationIssue("WARNING", "SPELLING_WARNING", "p", "same code again"),
                new ValidationIssue("INFO", "DATATYPE_NORMALIZED", "p", "normalized")
        ));

        assertEquals(" -- E:DEFAULT W:TYPE|SPELL I:NORMALIZED", comment);
    }

    @Test
    void shouldReturnEmptyTextWhenColumnHasNoIssue() {
        assertEquals("", new InlineIssueRenderer().render(List.of()));
    }
}
