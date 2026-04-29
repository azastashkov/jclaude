package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.jclaude.runtime.conversation.CompactionConfig;
import org.jclaude.runtime.session.Session;
import org.junit.jupiter.api.Test;

class DispatcherTest {

    @Test
    void help_command_is_non_mutating() {
        Session session = Session.create();
        Optional<SlashCommandResult> result =
                SlashCommandDispatcher.handle_slash_command("/help", session, CompactionConfig.defaults());
        assertThat(result).isPresent();
        assertThat(result.get().session()).isEqualTo(session);
        assertThat(result.get().message()).contains("Slash commands");
    }

    @Test
    void ignores_unknown_or_runtime_bound_slash_commands() {
        Session session = Session.create();
        assertThat(SlashCommandDispatcher.handle_slash_command("/unknown", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/status", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/sandbox", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/commit", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/pr", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/issue", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/teleport foo", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command(
                        "/debug-tool-call", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/model claude", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command(
                        "/permissions read-only", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/clear", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command(
                        "/clear --confirm", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/cost", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command(
                        "/resume session.json", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/config", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/config env", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/mcp list", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/diff", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/version", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command(
                        "/export note.txt", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/session list", session, CompactionConfig.defaults()))
                .isEmpty();
        assertThat(SlashCommandDispatcher.handle_slash_command("/plugins list", session, CompactionConfig.defaults()))
                .isEmpty();
    }
}
