package org.jclaude.commands;

/** A discovered agent definition. Mirrors Rust {@code AgentSummary}. */
public record AgentSummary(
        String name,
        String description,
        String model,
        String reasoning_effort,
        DefinitionSource source,
        DefinitionSource shadowed_by) {

    public AgentSummary with_shadowed_by(DefinitionSource shadow) {
        return new AgentSummary(name, description, model, reasoning_effort, source, shadow);
    }
}
