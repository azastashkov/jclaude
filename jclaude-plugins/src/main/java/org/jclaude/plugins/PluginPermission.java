package org.jclaude.plugins;

import java.util.Optional;

/** High-level filesystem capability declared by a plugin manifest's {@code permissions}. */
public enum PluginPermission {
    READ("read"),
    WRITE("write"),
    EXECUTE("execute");

    private final String label;

    PluginPermission(String label) {
        this.label = label;
    }

    public String as_str() {
        return label;
    }

    public static Optional<PluginPermission> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case "read" -> Optional.of(READ);
            case "write" -> Optional.of(WRITE);
            case "execute" -> Optional.of(EXECUTE);
            default -> Optional.empty();
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
