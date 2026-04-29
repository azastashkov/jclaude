package org.jclaude.plugins;

/** Origin classification for a plugin: built into the harness, bundled in resources, or external. */
public enum PluginKind {
    BUILTIN("builtin"),
    BUNDLED("bundled"),
    EXTERNAL("external");

    private final String label;

    PluginKind(String label) {
        this.label = label;
    }

    public String marketplace() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static PluginKind from_label(String value) {
        if (value == null) {
            return EXTERNAL;
        }
        return switch (value) {
            case "builtin" -> BUILTIN;
            case "bundled" -> BUNDLED;
            case "external" -> EXTERNAL;
            default -> EXTERNAL;
        };
    }
}
