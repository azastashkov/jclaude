package org.jclaude.commands;

import java.util.Optional;

/**
 * Parsed slash command. Sealed hierarchy mirrors the Rust {@code SlashCommand}
 * enum variant-for-variant.
 */
public sealed interface SlashCommand {

    /**
     * Parses {@code input}. Returns:
     * <ul>
     *   <li>empty {@link Optional} when the input is not a slash command (does not start with {@code /});</li>
     *   <li>a populated {@link Optional} when the input parses;</li>
     *   <li>throws {@link SlashCommandParseError} when the input starts with {@code /} but cannot be parsed.</li>
     * </ul>
     */
    static Optional<SlashCommand> parse(String input) {
        return SlashCommandParser.validate_slash_command_input(input);
    }

    /**
     * Returns the canonical slash-command name (e.g. {@code "/branch"}). Derived from the spec
     * table so it always matches what the user typed.
     */
    default String slash_name() {
        return SlashCommandNames.slash_name(this);
    }

    record Help() implements SlashCommand {}

    record Status() implements SlashCommand {}

    record Sandbox() implements SlashCommand {}

    record Compact() implements SlashCommand {}

    record Bughunter(String scope) implements SlashCommand {}

    record Commit() implements SlashCommand {}

    record Pr(String context) implements SlashCommand {}

    record Issue(String context) implements SlashCommand {}

    record Ultraplan(String task) implements SlashCommand {}

    record Teleport(String target) implements SlashCommand {}

    record DebugToolCall() implements SlashCommand {}

    record Model(String model) implements SlashCommand {}

    record Permissions(String mode) implements SlashCommand {}

    record Clear(boolean confirm) implements SlashCommand {}

    record Cost() implements SlashCommand {}

    record Resume(String session_path) implements SlashCommand {}

    record Config(String section) implements SlashCommand {}

    record Mcp(String action, String target) implements SlashCommand {}

    record Memory() implements SlashCommand {}

    record Init() implements SlashCommand {}

    record Diff() implements SlashCommand {}

    record Version() implements SlashCommand {}

    record Export(String path) implements SlashCommand {}

    record Session(String action, String target) implements SlashCommand {}

    record Plugins(String action, String target) implements SlashCommand {}

    record Agents(String args) implements SlashCommand {}

    record Skills(String args) implements SlashCommand {}

    record Doctor() implements SlashCommand {}

    record Login() implements SlashCommand {}

    record Logout() implements SlashCommand {}

    record Vim() implements SlashCommand {}

    record Upgrade() implements SlashCommand {}

    record Stats() implements SlashCommand {}

    record Share() implements SlashCommand {}

    record Feedback() implements SlashCommand {}

    record Files() implements SlashCommand {}

    record Fast() implements SlashCommand {}

    record Exit() implements SlashCommand {}

    record Summary() implements SlashCommand {}

    record Desktop() implements SlashCommand {}

    record Brief() implements SlashCommand {}

    record Advisor() implements SlashCommand {}

    record Stickers() implements SlashCommand {}

    record Insights() implements SlashCommand {}

    record Thinkback() implements SlashCommand {}

    record ReleaseNotes() implements SlashCommand {}

    record SecurityReview() implements SlashCommand {}

    record Keybindings() implements SlashCommand {}

    record PrivacySettings() implements SlashCommand {}

    record Plan(String mode) implements SlashCommand {}

    record Review(String scope) implements SlashCommand {}

    record Tasks(String args) implements SlashCommand {}

    record Theme(String name) implements SlashCommand {}

    record Voice(String mode) implements SlashCommand {}

    record Usage(String scope) implements SlashCommand {}

    record Rename(String name) implements SlashCommand {}

    record Copy(String target) implements SlashCommand {}

    record Hooks(String args) implements SlashCommand {}

    record Context(String action) implements SlashCommand {}

    record Color(String scheme) implements SlashCommand {}

    record Effort(String level) implements SlashCommand {}

    record Branch(String name) implements SlashCommand {}

    record Rewind(String steps) implements SlashCommand {}

    record Ide(String target) implements SlashCommand {}

    record Tag(String label) implements SlashCommand {}

    record OutputStyle(String style) implements SlashCommand {}

    record AddDir(String path) implements SlashCommand {}

    record History(String count) implements SlashCommand {}

    record Unknown(String text) implements SlashCommand {}
}
