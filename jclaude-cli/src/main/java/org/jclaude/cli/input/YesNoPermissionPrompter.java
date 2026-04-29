package org.jclaude.cli.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jclaude.runtime.permissions.PermissionPromptDecision;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.permissions.PermissionRequest;

/**
 * Reads a yes/no decision from stdin. Prompt text is printed to stdout in the same multi-line form
 * the upstream Rust CLI uses, so the parity harness can grep for "Permission approval required" and
 * "Approve this tool call? [y/N]:".
 */
public final class YesNoPermissionPrompter implements PermissionPrompter {

    private final BufferedReader reader;
    private final PrintStream prompt_sink;

    public YesNoPermissionPrompter() {
        this(System.in, System.out);
    }

    public YesNoPermissionPrompter(InputStream input, PrintStream prompt_sink) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.prompt_sink = prompt_sink;
    }

    @Override
    public PermissionPromptDecision decide(PermissionRequest request) {
        print_prompt(request);
        String line;
        try {
            line = reader.readLine();
        } catch (IOException error) {
            return new PermissionPromptDecision.Deny("permission prompt I/O error: " + error.getMessage());
        }
        // Stdin is piped (no terminal echo), so write a newline to terminate the
        // prompt line. Without this, the next renderer's output would be appended
        // to "Approve this tool call? [y/N]: ", breaking line-based JSON parsing.
        prompt_sink.println();
        prompt_sink.flush();
        if (line == null) {
            return new PermissionPromptDecision.Deny(
                    "tool '" + request.tool_name() + "' denied by user approval prompt");
        }
        String trimmed = line.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("y") || trimmed.equals("yes")) {
            return new PermissionPromptDecision.Allow();
        }
        return new PermissionPromptDecision.Deny("tool '" + request.tool_name() + "' denied by user approval prompt");
    }

    private void print_prompt(PermissionRequest request) {
        prompt_sink.println();
        prompt_sink.println("Permission approval required");
        prompt_sink.println("  Tool             " + request.tool_name());
        prompt_sink.println("  Current mode     " + request.current_mode().as_str());
        prompt_sink.println("  Required mode    " + request.required_mode().as_str());
        request.reason().ifPresent(reason -> prompt_sink.println("  Reason           " + reason));
        prompt_sink.println("  Input            " + request.input());
        prompt_sink.print("Approve this tool call? [y/N]: ");
        prompt_sink.flush();
    }
}
