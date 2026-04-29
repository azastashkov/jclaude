package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestSlashCommandsTest {

    @Test
    void suggests_closest_slash_commands_for_typos_and_aliases() {
        List<String> suggestions = SuggestSlashCommands.suggest_slash_commands("stats", 3);
        assertThat(suggestions).contains("/stats");
        assertThat(suggestions).contains("/status");
        assertThat(suggestions).hasSizeLessThanOrEqualTo(3);

        List<String> plugin_suggestions = SuggestSlashCommands.suggest_slash_commands("/plugns", 3);
        assertThat(plugin_suggestions).contains("/plugin");

        assertThat(SuggestSlashCommands.suggest_slash_commands("zzz", 3)).isEmpty();
    }
}
