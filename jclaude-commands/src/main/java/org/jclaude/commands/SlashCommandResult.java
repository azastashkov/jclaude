package org.jclaude.commands;

import org.jclaude.runtime.session.Session;

/**
 * Outcome of {@link SlashCommandDispatcher#handle_slash_command(String, Session,
 * org.jclaude.runtime.conversation.CompactionConfig)} that combines a user-visible message and the
 * (possibly mutated) session state.
 *
 * <p>Mirrors the Rust {@code SlashCommandResult} struct.
 */
public record SlashCommandResult(String message, Session session) {}
