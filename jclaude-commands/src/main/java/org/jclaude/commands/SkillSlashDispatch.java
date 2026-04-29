package org.jclaude.commands;

/**
 * How a {@code /skills} invocation should be routed.
 *
 * <p>Mirrors Rust {@code SkillSlashDispatch} sealed enum:
 * <ul>
 *   <li>{@link Local} — handled internally (list, install, help)</li>
 *   <li>{@link Invoke} — produce a {@code $skill ...} prompt that callers send to the model</li>
 * </ul>
 */
public sealed interface SkillSlashDispatch {

    record Local() implements SkillSlashDispatch {}

    record Invoke(String prompt) implements SkillSlashDispatch {}
}
