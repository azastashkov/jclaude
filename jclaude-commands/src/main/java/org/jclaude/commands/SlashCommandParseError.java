package org.jclaude.commands;

/** Error raised when {@link SlashCommand#parse(String)} cannot interpret the input. */
public final class SlashCommandParseError extends RuntimeException {

    public SlashCommandParseError(String message) {
        super(message);
    }
}
