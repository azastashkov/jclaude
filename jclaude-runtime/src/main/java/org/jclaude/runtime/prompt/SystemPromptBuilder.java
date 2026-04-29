package org.jclaude.runtime.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builder for the runtime system prompt and dynamic environment sections. */
public final class SystemPromptBuilder {

    public static final String SYSTEM_PROMPT_DYNAMIC_BOUNDARY = "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__";
    public static final String FRONTIER_MODEL_NAME = "Claude Opus 4.6";

    private static final int MAX_INSTRUCTION_FILE_CHARS = 4_000;
    private static final int MAX_TOTAL_INSTRUCTION_CHARS = 12_000;

    private Optional<String> output_style_name = Optional.empty();
    private Optional<String> output_style_prompt = Optional.empty();
    private Optional<String> os_name = Optional.empty();
    private Optional<String> os_version = Optional.empty();
    private final List<String> append_sections = new ArrayList<>();

    public SystemPromptBuilder with_output_style(String name, String prompt) {
        this.output_style_name = Optional.of(name);
        this.output_style_prompt = Optional.of(prompt);
        return this;
    }

    public SystemPromptBuilder with_os(String name, String version) {
        this.os_name = Optional.of(name);
        this.os_version = Optional.of(version);
        return this;
    }

    public SystemPromptBuilder append(String section) {
        append_sections.add(section);
        return this;
    }

    public String build_static_prompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Claude (").append(FRONTIER_MODEL_NAME).append(").\n\n");
        output_style_prompt.ifPresent(p -> sb.append(p).append("\n\n"));
        os_name.ifPresent(name -> sb.append("OS: ")
                .append(name)
                .append(os_version.map(v -> " " + v).orElse(""))
                .append("\n"));
        for (String section : append_sections) {
            sb.append(section).append("\n");
        }
        return sb.toString().trim();
    }

    public String build_dynamic_section(ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current date: ").append(ctx.current_date()).append("\n");
        sb.append("Working directory: ").append(ctx.cwd()).append("\n");
        ctx.git_context().ifPresent(g -> sb.append("\n").append(g.render()).append("\n"));
        ctx.git_status().ifPresent(s -> sb.append("\nGit status:\n").append(s));
        ctx.git_diff().ifPresent(d -> sb.append("\nGit diff:\n").append(d));
        int total_chars = 0;
        for (ContextFile file : ctx.instruction_files()) {
            String c = file.content();
            if (c.length() > MAX_INSTRUCTION_FILE_CHARS) {
                c = c.substring(0, MAX_INSTRUCTION_FILE_CHARS);
            }
            if (total_chars + c.length() > MAX_TOTAL_INSTRUCTION_CHARS) {
                break;
            }
            sb.append("\n--- ")
                    .append(file.path().getFileName())
                    .append(" ---\n")
                    .append(c);
            total_chars += c.length();
        }
        return sb.toString();
    }

    public String build(ProjectContext ctx) {
        return build_static_prompt() + "\n" + SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n" + build_dynamic_section(ctx);
    }
}
