package org.jclaude.cli.render;

import java.io.PrintStream;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;

/** Renders a {@link TurnSummary} as plain text on stdout. */
public final class TextOutputRenderer {

    private final PrintStream out;

    public TextOutputRenderer() {
        this(System.out);
    }

    public TextOutputRenderer(PrintStream out) {
        this.out = out;
    }

    public void render(TurnSummary summary, boolean compact) {
        String message_text = concatenate_assistant_text(summary);
        if (compact) {
            if (!message_text.isEmpty()) {
                out.println(message_text);
            }
            out.flush();
            return;
        }

        if (!message_text.isEmpty()) {
            out.println(message_text);
        }
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolUse use) {
                    out.println("[tool_use] " + use.name() + " " + use.input());
                }
            }
        }
        for (ConversationMessage message : summary.tool_results()) {
            if (message.role() != MessageRole.TOOL) {
                continue;
            }
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolResult result) {
                    String label = result.is_error() ? "[tool_error]" : "[tool_result]";
                    out.println(label + " " + result.tool_name() + ": " + result.output());
                }
            }
        }
        out.println("[turn] iterations=" + summary.iterations()
                + " input_tokens=" + summary.usage().input_tokens()
                + " output_tokens=" + summary.usage().output_tokens());
        out.flush();
    }

    private static String concatenate_assistant_text(TurnSummary summary) {
        StringBuilder text = new StringBuilder();
        for (ConversationMessage message : summary.assistant_messages()) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.Text t) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(t.text());
                }
            }
        }
        return text.toString();
    }
}
