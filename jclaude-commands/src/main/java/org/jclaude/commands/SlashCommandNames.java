package org.jclaude.commands;

/** Maps {@link SlashCommand} variants to their canonical {@code "/foo"} display names. */
final class SlashCommandNames {

    private SlashCommandNames() {}

    static String slash_name(SlashCommand command) {
        return switch (command) {
            case SlashCommand.Help h -> "/help";
            case SlashCommand.Clear c -> "/clear";
            case SlashCommand.Compact c -> "/compact";
            case SlashCommand.Cost c -> "/cost";
            case SlashCommand.Doctor d -> "/doctor";
            case SlashCommand.Config c -> "/config";
            case SlashCommand.Memory m -> "/memory";
            case SlashCommand.History h -> "/history";
            case SlashCommand.Diff d -> "/diff";
            case SlashCommand.Status s -> "/status";
            case SlashCommand.Stats s -> "/stats";
            case SlashCommand.Version v -> "/version";
            case SlashCommand.Commit c -> "/commit";
            case SlashCommand.Pr p -> "/pr";
            case SlashCommand.Issue i -> "/issue";
            case SlashCommand.Init i -> "/init";
            case SlashCommand.Bughunter b -> "/bughunter";
            case SlashCommand.Ultraplan u -> "/ultraplan";
            case SlashCommand.Teleport t -> "/teleport";
            case SlashCommand.DebugToolCall d -> "/debug-tool-call";
            case SlashCommand.Resume r -> "/resume";
            case SlashCommand.Model m -> "/model";
            case SlashCommand.Permissions p -> "/permissions";
            case SlashCommand.Session s -> "/session";
            case SlashCommand.Plugins p -> "/plugins";
            case SlashCommand.Login l -> "/login";
            case SlashCommand.Logout l -> "/logout";
            case SlashCommand.Vim v -> "/vim";
            case SlashCommand.Upgrade u -> "/upgrade";
            case SlashCommand.Share s -> "/share";
            case SlashCommand.Feedback f -> "/feedback";
            case SlashCommand.Files f -> "/files";
            case SlashCommand.Fast f -> "/fast";
            case SlashCommand.Exit e -> "/exit";
            case SlashCommand.Summary s -> "/summary";
            case SlashCommand.Desktop d -> "/desktop";
            case SlashCommand.Brief b -> "/brief";
            case SlashCommand.Advisor a -> "/advisor";
            case SlashCommand.Stickers s -> "/stickers";
            case SlashCommand.Insights i -> "/insights";
            case SlashCommand.Thinkback t -> "/thinkback";
            case SlashCommand.ReleaseNotes r -> "/release-notes";
            case SlashCommand.SecurityReview s -> "/security-review";
            case SlashCommand.Keybindings k -> "/keybindings";
            case SlashCommand.PrivacySettings p -> "/privacy-settings";
            case SlashCommand.Plan p -> "/plan";
            case SlashCommand.Review r -> "/review";
            case SlashCommand.Tasks t -> "/tasks";
            case SlashCommand.Theme t -> "/theme";
            case SlashCommand.Voice v -> "/voice";
            case SlashCommand.Usage u -> "/usage";
            case SlashCommand.Rename r -> "/rename";
            case SlashCommand.Copy c -> "/copy";
            case SlashCommand.Hooks h -> "/hooks";
            case SlashCommand.Context c -> "/context";
            case SlashCommand.Color c -> "/color";
            case SlashCommand.Effort e -> "/effort";
            case SlashCommand.Branch b -> "/branch";
            case SlashCommand.Rewind r -> "/rewind";
            case SlashCommand.Ide i -> "/ide";
            case SlashCommand.Tag t -> "/tag";
            case SlashCommand.OutputStyle o -> "/output-style";
            case SlashCommand.AddDir a -> "/add-dir";
            case SlashCommand.Sandbox s -> "/sandbox";
            case SlashCommand.Mcp m -> "/mcp";
            case SlashCommand.Export e -> "/export";
            case SlashCommand.Agents a -> "/agents";
            case SlashCommand.Skills s -> "/skills";
            case SlashCommand.Unknown u -> "/unknown";
        };
    }
}
