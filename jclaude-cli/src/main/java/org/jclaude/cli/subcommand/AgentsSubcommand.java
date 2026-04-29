package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import org.jclaude.cli.OutputFormat;
import org.jclaude.commands.AgentsHandler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jclaude agents} — list discovered agents. Delegates to {@link AgentsHandler}, which is
 * already used by the {@code /agents} slash command.
 */
@Command(name = "agents", mixinStandardHelpOptions = true, description = "List discovered agent definitions.")
public final class AgentsSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Parameters(arity = "0..*", description = "Optional sub-action (list|help). Default: list.")
    private java.util.List<String> tail_args;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        Path cwd = Paths.get("").toAbsolutePath();
        String args = (tail_args == null || tail_args.isEmpty()) ? null : String.join(" ", tail_args);
        try {
            if (fmt == OutputFormat.JSON) {
                ObjectNode result = AgentsHandler.handle_agents_slash_command_json(args, cwd);
                System.out.println(result.toPrettyString());
            } else {
                System.out.println(AgentsHandler.handle_agents_slash_command(args, cwd));
            }
            return 0;
        } catch (IOException error) {
            System.err.println("error: " + error.getMessage());
            return 1;
        }
    }
}
