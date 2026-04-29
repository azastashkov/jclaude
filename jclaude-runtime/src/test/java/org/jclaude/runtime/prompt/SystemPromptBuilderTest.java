package org.jclaude.runtime.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemPromptBuilderTest {

    @Test
    void produces_static_prompt_with_model_and_os() {
        SystemPromptBuilder builder = new SystemPromptBuilder().with_os("macOS", "14");

        String prompt = builder.build_static_prompt();

        assertThat(prompt).contains("Claude Opus 4.6");
        assertThat(prompt).contains("macOS 14");
    }

    @Test
    void renders_boundary_marker_between_static_and_dynamic(@TempDir Path tmp) throws IOException {
        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");
        SystemPromptBuilder builder = new SystemPromptBuilder();

        String prompt = builder.build(ctx);

        assertThat(prompt).contains(SystemPromptBuilder.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        assertThat(prompt).contains("Current date: 2026-04-29");
        assertThat(prompt).contains("Working directory: " + tmp);
    }

    @Test
    void embeds_instruction_files_when_present(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("CLAUDE.md"), "follow these rules");

        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");
        String prompt = new SystemPromptBuilder().build(ctx);

        assertThat(prompt).contains("CLAUDE.md");
        assertThat(prompt).contains("follow these rules");
    }

    @Test
    void appends_extra_sections() {
        SystemPromptBuilder builder = new SystemPromptBuilder().append("custom directive");

        String prompt = builder.build_static_prompt();

        assertThat(prompt).contains("custom directive");
    }

    @Test
    void renders_claude_code_style_sections_with_project_context(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("CLAUDE.md"), "rules");
        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");

        SystemPromptBuilder builder = new SystemPromptBuilder().with_os("macOS", "14");
        String prompt = builder.build(ctx);

        assertThat(prompt).contains("Claude Opus 4.6");
        assertThat(prompt).contains("OS: macOS 14");
        assertThat(prompt).contains(SystemPromptBuilder.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        assertThat(prompt).contains("Current date: 2026-04-29");
        assertThat(prompt).contains("Working directory: " + tmp);
        assertThat(prompt).contains("rules");
    }

    @Test
    void truncates_instruction_content_to_budget(@TempDir Path tmp) throws IOException {
        // Java enforces both per-file (4_000) and total (12_000) instruction caps.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            sb.append('x');
        }
        Files.writeString(tmp.resolve("CLAUDE.md"), sb.toString());

        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");
        String prompt = new SystemPromptBuilder().build(ctx);

        // Prompt should not include the full 5000 chars — it caps at 4000 per file.
        assertThat(prompt.length()).isLessThan(5000 + 1500);
    }

    @Test
    void discovers_dot_claude_instructions_markdown(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".claude"));
        Files.writeString(tmp.resolve(".claude/CLAUDE.md"), "dot-claude rules");

        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");

        assertThat(ctx.instruction_files()).anyMatch(f -> f.content().equals("dot-claude rules"));
    }

    @Test
    void renders_instruction_file_metadata(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("CLAUDE.md"), "metadata-tagged rules");
        ProjectContext ctx = ProjectContext.discover(tmp, "2026-04-29");

        String prompt = new SystemPromptBuilder().build(ctx);

        // The dynamic section labels each instruction file by name.
        assertThat(prompt).contains("CLAUDE.md");
        assertThat(prompt).contains("metadata-tagged rules");
    }
}
