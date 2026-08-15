package com.behsazan.schemaforge.diagram.graphviz;

/**
 * Graphviz-specific readability options layered on top of the database-independent
 * {@code DiagramExportOptions}.
 *
 * <p>The defaults preserve Graphviz Phase 1 output exactly: all selected tables are rendered,
 * foreign-key labels are shown, and dependency nodes are not clustered unless explicitly
 * requested.</p>
 */
public record GraphvizRenderOptions(
        boolean includeDisconnectedTables,
        boolean showFkLabels,
        boolean clusterBySchema) {

    public static GraphvizRenderOptions defaults() {
        return new GraphvizRenderOptions(true, true, false);
    }

    public static GraphvizRenderOptions fullClustered() {
        return new GraphvizRenderOptions(true, true, true);
    }

    public static GraphvizRenderOptions compact() {
        return new GraphvizRenderOptions(false, true, true);
    }

    public static GraphvizRenderOptions overview() {
        return new GraphvizRenderOptions(false, false, true);
    }

    public GraphvizRenderOptions withClusterBySchema(boolean value) {
        return new GraphvizRenderOptions(includeDisconnectedTables, showFkLabels, value);
    }
}
