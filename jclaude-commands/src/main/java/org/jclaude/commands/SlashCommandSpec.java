package org.jclaude.commands;

import java.util.List;

/**
 * Canonical metadata for a slash command.
 *
 * <p>Mirrors the Rust {@code SlashCommandSpec} struct verbatim:
 * <pre>{@code
 * pub struct SlashCommandSpec {
 *     pub name: &'static str,
 *     pub aliases: &'static [&'static str],
 *     pub summary: &'static str,
 *     pub argument_hint: Option<&'static str>,
 *     pub resume_supported: bool,
 * }
 * }</pre>
 */
public record SlashCommandSpec(
        String name, List<String> aliases, String summary, String argument_hint, boolean resume_supported) {

    public SlashCommandSpec {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** Convenience constructor that mirrors the Rust struct literal with no aliases. */
    public static SlashCommandSpec of(String name, String summary, String argument_hint, boolean resume_supported) {
        return new SlashCommandSpec(name, List.of(), summary, argument_hint, resume_supported);
    }

    /** Convenience constructor with aliases. */
    public static SlashCommandSpec withAliases(
            String name, List<String> aliases, String summary, String argument_hint, boolean resume_supported) {
        return new SlashCommandSpec(name, aliases, summary, argument_hint, resume_supported);
    }
}
