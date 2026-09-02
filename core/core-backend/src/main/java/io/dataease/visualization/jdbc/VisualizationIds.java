package io.dataease.visualization.jdbc;

/**
 * Bind visualization varchar id/pid as an exact decimal string.
 * Long JDBC parameters trigger MySQL numeric comparison and can collide above 2^53.
 */
public final class VisualizationIds {

    private VisualizationIds() {
    }

    public static String exact(Long id) {
        return id == null ? null : Long.toString(id);
    }
}
