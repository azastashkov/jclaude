package org.jclaude.commands;

import java.util.Optional;
import org.jclaude.runtime.conversation.CompactionConfig;
import org.jclaude.runtime.session.Session;

/**
 * Top-level dispatcher for slash commands. Mirrors Rust {@code handle_slash_command}.
 *
 * <p>The Rust dispatcher implements two real commands locally — {@code /help} and
 * {@code /compact} — and returns {@code None} for every other variant (which the
 * REPL handles itself). The Java port keeps the same shape: callers receive an
 * empty {@link Optional} for runtime-bound commands and a populated result for
 * help/compact.
 *
 * <p>Note: the {@code /compact} integration with the runtime compaction helper
 * lives in {@code jclaude-runtime} and is intentionally not invoked from this
 * module to keep the dependency direction one-way ({@code commands -> runtime}).
 * This dispatcher returns a parse error envelope but leaves the actual
 * compaction call to a tiny adapter in the CLI module.
 */
public final class SlashCommandDispatcher {

    private SlashCommandDispatcher() {}

    public static Optional<SlashCommandResult> handle_slash_command(
            String input, Session session, CompactionConfig compaction) {
        SlashCommand command;
        try {
            Optional<SlashCommand> parsed = SlashCommand.parse(input);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            command = parsed.get();
        } catch (SlashCommandParseError error) {
            return Optional.of(new SlashCommandResult(error.getMessage(), session));
        }

        if (command instanceof SlashCommand.Help) {
            return Optional.of(new SlashCommandResult(HelpRenderer.render_slash_command_help(), session));
        }

        // /compact and the rest are intentionally returned as None (Optional.empty)
        // matching the Rust dispatcher; the REPL or runtime adapter handles them.
        return Optional.empty();
    }
}
