package org.jclaude.commands;

/** Origin (skills directory vs. legacy commands directory) of a discovered skill. */
public enum SkillOrigin {
    SKILLS_DIR,
    LEGACY_COMMANDS_DIR;

    public String detail_label() {
        return switch (this) {
            case SKILLS_DIR -> null;
            case LEGACY_COMMANDS_DIR -> "legacy /commands";
        };
    }

    public String json_id() {
        return switch (this) {
            case SKILLS_DIR -> "skills_dir";
            case LEGACY_COMMANDS_DIR -> "legacy_commands_dir";
        };
    }
}
