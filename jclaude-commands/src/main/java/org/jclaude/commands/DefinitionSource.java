package org.jclaude.commands;

/** Where an agent or skill definition was loaded from. Mirrors Rust {@code DefinitionSource}. */
public enum DefinitionSource {
    PROJECT_CLAW,
    PROJECT_CODEX,
    PROJECT_CLAUDE,
    USER_CLAW_CONFIG_HOME,
    USER_CODEX_HOME,
    USER_CLAW,
    USER_CODEX,
    USER_CLAUDE;

    public DefinitionScope report_scope() {
        return switch (this) {
            case PROJECT_CLAW, PROJECT_CODEX, PROJECT_CLAUDE -> DefinitionScope.PROJECT;
            case USER_CLAW_CONFIG_HOME, USER_CODEX_HOME -> DefinitionScope.USER_CONFIG_HOME;
            case USER_CLAW, USER_CODEX, USER_CLAUDE -> DefinitionScope.USER_HOME;
        };
    }

    /** Returns the same human-readable label as {@link DefinitionScope#label()}. */
    public String label() {
        return report_scope().label();
    }

    /** Stable JSON id mirroring Rust {@code definition_source_id}. */
    public String json_id() {
        return switch (this) {
            case PROJECT_CLAW, PROJECT_CODEX, PROJECT_CLAUDE -> "project_claw";
            case USER_CLAW_CONFIG_HOME, USER_CODEX_HOME -> "user_claw_config_home";
            case USER_CLAW, USER_CODEX, USER_CLAUDE -> "user_claw";
        };
    }
}
