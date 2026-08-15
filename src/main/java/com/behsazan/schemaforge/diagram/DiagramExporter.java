package com.behsazan.schemaforge.diagram;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.Collection;

/** Renders canonical database tables into a textual diagram representation. */
public interface DiagramExporter {
    String export(Collection<Table> tables, DiagramExportOptions options);
}
