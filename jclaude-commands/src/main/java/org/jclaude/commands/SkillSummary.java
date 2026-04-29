package org.jclaude.commands;

/** A discovered skill definition. Mirrors Rust {@code SkillSummary}. */
public record SkillSummary(
        String name, String description, DefinitionSource source, DefinitionSource shadowed_by, SkillOrigin origin) {

    public SkillSummary with_shadowed_by(DefinitionSource shadow) {
        return new SkillSummary(name, description, source, shadow, origin);
    }
}
