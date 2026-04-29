package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jclaude.plugins.PluginRegistry;
import org.jclaude.runtime.permissions.PermissionMode;
import org.jclaude.runtime.permissions.PermissionPolicy;
import org.jclaude.tools.ToolSpec;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WireRunner} static helpers. Mirrors the inline {@code mod tests} block in
 * {@code crates/rusty-claude-cli/src/main.rs} that exercises {@code filter_tool_specs},
 * {@code permission_policy}, and the prompt-merge helpers.
 */
final class WireRunnerTest {

    @Test
    void filtered_tool_specs_respect_allowlist() {
        // Mirrors `filtered_tool_specs_respect_allowlist` at
        // crates/rusty-claude-cli/src/main.rs:11484.
        List<ToolSpec> filtered = WireRunner.filtered_tool_specs("read_file,grep_search");

        List<String> names = filtered.stream().map(ToolSpec::name).toList();
        assertThat(names).containsExactly("read_file", "grep_search");
    }

    @Test
    void filtered_tool_specs_returns_full_mvp_set_when_allowlist_blank() {
        // Java equivalent of "Some(empty allowlist) => everything passes".
        List<ToolSpec> filtered_empty = WireRunner.filtered_tool_specs("");
        List<ToolSpec> filtered_null = WireRunner.filtered_tool_specs(null);
        List<ToolSpec> filtered_blank = WireRunner.filtered_tool_specs("   ");

        assertThat(filtered_empty).hasSizeGreaterThan(5);
        assertThat(filtered_null).hasSize(filtered_empty.size());
        assertThat(filtered_blank).hasSize(filtered_empty.size());
        assertThat(filtered_empty.stream().map(ToolSpec::name)).contains("read_file", "write_file", "bash");
    }

    @Test
    void filtered_tool_specs_skips_unknown_names() {
        // The filter is order-preserving over the full all_tool_specs() catalog (50 entries),
        // so we assert membership rather than relative position — bash and read_file may sit
        // in any order in the catalog.
        List<ToolSpec> filtered = WireRunner.filtered_tool_specs("read_file,does_not_exist,bash");

        List<String> names = filtered.stream().map(ToolSpec::name).toList();
        assertThat(names).containsExactlyInAnyOrder("read_file", "bash");
    }

    @Test
    void filtered_tool_specs_trims_whitespace_in_csv_entries() {
        List<ToolSpec> filtered = WireRunner.filtered_tool_specs("  read_file ,  bash  ");

        assertThat(filtered.stream().map(ToolSpec::name)).containsExactlyInAnyOrder("read_file", "bash");
    }

    @Test
    void build_policy_applies_default_tool_requirements() {
        // Mirrors `permission_policy_*` tests at
        // crates/rusty-claude-cli/src/main.rs:11509.
        PermissionPolicy policy = WireRunner.build_policy(PermissionMode.READ_ONLY, null);

        assertThat(policy.required_mode_for("read_file")).isEqualTo(PermissionMode.READ_ONLY);
        assertThat(policy.required_mode_for("write_file")).isEqualTo(PermissionMode.WORKSPACE_WRITE);
        assertThat(policy.required_mode_for("edit_file")).isEqualTo(PermissionMode.WORKSPACE_WRITE);
        assertThat(policy.required_mode_for("bash")).isEqualTo(PermissionMode.DANGER_FULL_ACCESS);
    }

    @Test
    void build_policy_with_no_plugins_does_not_fail() {
        PermissionPolicy policy =
                WireRunner.build_policy(PermissionMode.WORKSPACE_WRITE, "read_file", PluginRegistry.empty());

        assertThat(policy).isNotNull();
        assertThat(policy.required_mode_for("read_file")).isEqualTo(PermissionMode.READ_ONLY);
    }

    @Test
    void join_prompt_args_returns_dash_p_when_set() {
        // Mirrors the `parses_*` arg-join tests at
        // crates/rusty-claude-cli/src/main.rs:9686 onward, adapted for the
        // Java MVP CLI surface where positional words and -p coexist.
        String joined = WireRunner.join_prompt_args("explain this", List.of("ignored", "tail"));
        assertThat(joined).isEqualTo("explain this");
    }

    @Test
    void join_prompt_args_returns_positional_when_dash_p_blank() {
        assertThat(WireRunner.join_prompt_args(null, List.of("hello", "world"))).isEqualTo("hello world");
        assertThat(WireRunner.join_prompt_args("", List.of("alpha", "beta"))).isEqualTo("alpha beta");
        assertThat(WireRunner.join_prompt_args("   ", List.of("alpha"))).isEqualTo("alpha");
    }

    @Test
    void join_prompt_args_returns_null_when_nothing_supplied() {
        assertThat(WireRunner.join_prompt_args(null, null)).isNull();
        assertThat(WireRunner.join_prompt_args(null, List.of())).isNull();
        assertThat(WireRunner.join_prompt_args("", null)).isNull();
    }
}
