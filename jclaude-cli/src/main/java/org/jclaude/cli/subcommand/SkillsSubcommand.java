package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import org.jclaude.cli.OutputFormat;
import org.jclaude.commands.SkillsHandler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jclaude skills} — list discovered skills. Delegates to {@link SkillsHandler}, which is
 * already used by the {@code /skills} slash command.
 */
@Command(name = "skills", mixinStandardHelpOptions = true, description = "List discovered skill definitions.")
public final class SkillsSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Parameters(arity = "0..*", description = "Optional sub-action (list|install <path>|help). Default: list.")
    private java.util.List<String> tail_args;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        Path cwd = Paths.get("").toAbsolutePath();
        String args = (tail_args == null || tail_args.isEmpty()) ? null : String.join(" ", tail_args);
        try {
            if (fmt == OutputFormat.JSON) {
                ObjectNode result = SkillsHandler.handle_skills_slash_command_json(args, cwd);
                System.out.println(result.toPrettyString());
            } else {
                System.out.println(SkillsHandler.handle_skills_slash_command(args, cwd));
            }
            return 0;
        } catch (IOException error) {
            System.err.println("error: " + error.getMessage());
            return 1;
        }
    }
}
