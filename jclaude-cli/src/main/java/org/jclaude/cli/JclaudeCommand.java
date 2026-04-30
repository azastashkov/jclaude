package org.jclaude.cli;

import java.util.List;
import java.util.concurrent.Callable;
import org.jclaude.cli.subcommand.AgentsSubcommand;
import org.jclaude.cli.subcommand.ConfigSubcommand;
import org.jclaude.cli.subcommand.DoctorSubcommand;
import org.jclaude.cli.subcommand.InitSubcommand;
import org.jclaude.cli.subcommand.McpSubcommand;
import org.jclaude.cli.subcommand.SandboxSubcommand;
import org.jclaude.cli.subcommand.SkillsSubcommand;
import org.jclaude.cli.subcommand.StatusSubcommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Top-level Picocli command for the `jclaude` CLI (Phase 1 MVP). */
@Command(
        name = "jclaude",
        version = "jclaude 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        description = "Run a single turn against a Claude-compatible model.",
        subcommands = {
            DoctorSubcommand.class,
            StatusSubcommand.class,
            ConfigSubcommand.class,
            InitSubcommand.class,
            SandboxSubcommand.class,
            AgentsSubcommand.class,
            McpSubcommand.class,
            SkillsSubcommand.class,
        })
public final class JclaudeCommand implements Callable<Integer> {

    @Option(
            names = {"--model"},
            description = "Model id or alias (default: ${DEFAULT-VALUE}).",
            defaultValue = "claude-sonnet-4-6")
    private String model;

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text",
            converter = OutputFormatConverter.class)
    private OutputFormat output_format;

    @Option(
            names = {"--permission-mode"},
            description =
                    "Permission mode: read-only, workspace-write, or danger-full-access (default: ${DEFAULT-VALUE}).",
            defaultValue = "read-only",
            converter = PermissionModeConverter.class)
    private PermissionModeOption permission_mode_option;

    @Option(
            names = {"--allowedTools"},
            description = "Comma-separated tool name whitelist (default: all MVP tools).")
    private String allowed_tools_csv;

    @Option(
            names = {"-p", "--print"},
            description = "One-shot prompt. Alternative to passing a positional argument.")
    private String dash_p_prompt;

    @Option(
            names = {"--resume"},
            description = "Resume a session from this id or path (a JSONL session log).")
    private String resume;

    @Option(
            names = {"--compact"},
            description = "Compact text output (only the assistant text, no tool trace).")
    private boolean compact;

    @Option(
            names = {"--dangerously-skip-permissions"},
            description = "Approve every permission prompt automatically.")
    private boolean dangerously_skip_permissions;

    @Option(
            names = {"--max-tokens"},
            description = "Maximum output tokens (default: max for model).",
            defaultValue = "0")
    private long max_tokens;

    @Option(
            names = {"--style"},
            description = "REPL output style: jclaude (rounded tool boxes, default) or claude-code "
                    + "(bullet-prefixed inline tool calls).",
            defaultValue = "jclaude",
            converter = OutputStyleConverter.class)
    private OutputStyle output_style;

    @Parameters(arity = "0..*", description = "Positional prompt words (alternative to -p).")
    private List<String> positional_prompt;

    public String model() {
        return model;
    }

    public OutputFormat output_format() {
        return output_format;
    }

    public PermissionModeOption permission_mode_option() {
        return permission_mode_option;
    }

    public String allowed_tools_csv() {
        return allowed_tools_csv;
    }

    public String dash_p_prompt() {
        return dash_p_prompt;
    }

    public String resume() {
        return resume;
    }

    public boolean compact() {
        return compact;
    }

    public boolean dangerously_skip_permissions() {
        return dangerously_skip_permissions;
    }

    public long max_tokens() {
        return max_tokens;
    }

    public OutputStyle output_style() {
        return output_style;
    }

    public List<String> positional_prompt() {
        return positional_prompt;
    }

    @Override
    public Integer call() {
        String prompt = WireRunner.join_prompt_args(dash_p_prompt, positional_prompt);
        return new WireRunner(this).run(prompt);
    }

    /** picocli converter for {@link OutputFormat}. */
    public static final class OutputFormatConverter implements picocli.CommandLine.ITypeConverter<OutputFormat> {
        @Override
        public OutputFormat convert(String value) {
            return OutputFormat.parse(value);
        }
    }

    /** picocli converter for {@link PermissionModeOption}. */
    public static final class PermissionModeConverter
            implements picocli.CommandLine.ITypeConverter<PermissionModeOption> {
        @Override
        public PermissionModeOption convert(String value) {
            return PermissionModeOption.parse(value);
        }
    }

    /** picocli converter for {@link OutputStyle}. */
    public static final class OutputStyleConverter implements picocli.CommandLine.ITypeConverter<OutputStyle> {
        @Override
        public OutputStyle convert(String value) {
            return OutputStyle.parse(value);
        }
    }
}
