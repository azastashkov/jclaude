package org.jclaude.commands;

/** Scope grouping used in agent/skill reports. Mirrors Rust {@code DefinitionScope}. */
public enum DefinitionScope {
    PROJECT,
    USER_CONFIG_HOME,
    USER_HOME;

    public String label() {
        return switch (this) {
            case PROJECT -> "Project roots";
            case USER_CONFIG_HOME -> "User config roots";
            case USER_HOME -> "User home roots";
        };
    }
}
