package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidDiagramControllerOptionsTest {

    @Test
    void parsesTableWithDependenciesOptions() {
        DiagramExportOptions options = MermaidDiagramController.options(
                "dependency", "table-with-dependencies", null,
                "TSTSHMA.ACCOUNT", null, 2,
                false, false, true, true, false);

        assertEquals(DiagramType.DEPENDENCY, options.type());
        assertEquals(DiagramScope.TABLE_WITH_DEPENDENCIES, options.scope());
        assertEquals("TSTSHMA.ACCOUNT", options.rootTable().toString());
        assertEquals(2, options.dependencyDepth());
        assertTrue(options.includeForeignKeys());
    }

    @Test
    void parsesSelectedTablesCsv() {
        DiagramExportOptions options = MermaidDiagramController.options(
                "er", "selected-tables", null, null,
                "TSTSHMA.CUSTOMER,TSTSHMA.ACCOUNT", 1,
                true, true, true, true, false);

        assertEquals(2, options.selectedTables().size());
    }

    @Test
    void rejectsUnqualifiedRootName() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> MermaidDiagramController.options(
                        "er", "table", null, "ACCOUNT", null, 1,
                        true, true, true, true, false));

        assertTrue(exception.getMessage().contains("SCHEMA.TABLE"), exception.getMessage());
    }
}
