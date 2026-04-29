package org.jclaude.runtime.compaction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jclaude.runtime.session.ContentBlock;
import org.jclaude.runtime.session.ConversationMessage;
import org.jclaude.runtime.session.MessageRole;
import org.jclaude.runtime.session.Session;

/** Session compaction. Verbatim port of the Rust {@code compact} module. */
public final class Compaction {

    static final String COMPACT_CONTINUATION_PREAMBLE =
            "This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.\n\n";
    static final String COMPACT_RECENT_MESSAGES_NOTE = "Recent messages are preserved verbatim.";
    static final String COMPACT_DIRECT_RESUME_INSTRUCTION =
            "Continue the conversation from where it left off without asking the user any further questions. Resume directly — do not acknowledge the summary, do not recap what was happening, and do not preface with continuation text.";

    private Compaction() {}

    /** Roughly estimates the token footprint of the current session transcript. */
    public static int estimate_session_tokens(Session session) {
        int total = 0;
        for (ConversationMessage message : session.messages()) {
            total += estimate_message_tokens(message);
        }
        return total;
    }

    /** Returns {@code true} when the session exceeds the configured compaction budget. */
    public static boolean should_compact(Session session, CompactionConfig config) {
        List<ConversationMessage> messages = session.messages();
        int start = compacted_summary_prefix_len(messages);
        List<ConversationMessage> compactable = messages.subList(start, messages.size());

        if (compactable.size() <= config.preserve_recent_messages()) {
            return false;
        }
        int total = 0;
        for (ConversationMessage message : compactable) {
            total += estimate_message_tokens(message);
        }
        return total >= config.max_estimated_tokens();
    }

    /** Normalizes a compaction summary into user-facing continuation text. */
    public static String format_compact_summary(String summary) {
        String without_analysis = strip_tag_block(summary, "analysis");
        String formatted;
        Optional<String> content = extract_tag_block(without_analysis, "summary");
        if (content.isPresent()) {
            formatted = without_analysis.replace(
                    "<summary>" + content.get() + "</summary>",
                    "Summary:\n" + content.get().trim());
        } else {
            formatted = without_analysis;
        }
        return collapse_blank_lines(formatted).trim();
    }

    /** Builds the synthetic system message used after session compaction. */
    public static String get_compact_continuation_message(
            String summary, boolean suppress_follow_up_questions, boolean recent_messages_preserved) {
        StringBuilder base = new StringBuilder();
        base.append(COMPACT_CONTINUATION_PREAMBLE).append(format_compact_summary(summary));

        if (recent_messages_preserved) {
            base.append("\n\n");
            base.append(COMPACT_RECENT_MESSAGES_NOTE);
        }
        if (suppress_follow_up_questions) {
            base.append('\n');
            base.append(COMPACT_DIRECT_RESUME_INSTRUCTION);
        }
        return base.toString();
    }

    /** Compacts a session by summarizing older messages and preserving the recent tail. */
    public static CompactionResult compact_session(Session session, CompactionConfig config) {
        if (!should_compact(session, config)) {
            return new CompactionResult("", "", session, 0);
        }

        List<ConversationMessage> messages = session.messages();
        Optional<String> existing_summary =
                messages.isEmpty() ? Optional.empty() : extract_existing_compacted_summary(messages.get(0));
        int compacted_prefix_len = existing_summary.isPresent() ? 1 : 0;
        int raw_keep_from = Math.max(0, messages.size() - config.preserve_recent_messages());

        int keep_from = raw_keep_from;
        while (true) {
            if (keep_from == 0 || keep_from <= compacted_prefix_len) {
                break;
            }
            ConversationMessage first_preserved = messages.get(keep_from);
            boolean starts_with_tool_result = !first_preserved.blocks().isEmpty()
                    && first_preserved.blocks().get(0) instanceof ContentBlock.ToolResult;
            if (!starts_with_tool_result) {
                break;
            }
            ConversationMessage preceding = messages.get(keep_from - 1);
            boolean preceding_has_tool_use =
                    preceding.blocks().stream().anyMatch(b -> b instanceof ContentBlock.ToolUse);
            if (preceding_has_tool_use) {
                keep_from = Math.max(0, keep_from - 1);
                break;
            }
            keep_from = Math.max(0, keep_from - 1);
        }

        List<ConversationMessage> removed = new ArrayList<>(messages.subList(compacted_prefix_len, keep_from));
        List<ConversationMessage> preserved = new ArrayList<>(messages.subList(keep_from, messages.size()));
        String summary = merge_compact_summaries(existing_summary.orElse(null), summarize_messages(removed));
        String formatted_summary = format_compact_summary(summary);
        String continuation = get_compact_continuation_message(summary, true, !preserved.isEmpty());

        List<ConversationMessage> compacted_messages = new ArrayList<>();
        compacted_messages.add(
                new ConversationMessage(MessageRole.SYSTEM, List.of(new ContentBlock.Text(continuation)), null));
        compacted_messages.addAll(preserved);

        Session compacted_session = clone_with_messages(session, compacted_messages, summary, removed.size());
        return new CompactionResult(summary, formatted_summary, compacted_session, removed.size());
    }

    private static Session clone_with_messages(
            Session source, List<ConversationMessage> next, String summary, int removed_count) {
        Session compacted = Session.create();
        Optional<Path> root = source.workspace_root();
        root.ifPresent(compacted::with_workspace_root);
        source.model().ifPresent(compacted::set_model);
        compacted.replace_messages_for_compaction(next, summary, removed_count);
        return compacted;
    }

    private static int compacted_summary_prefix_len(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        return extract_existing_compacted_summary(messages.get(0)).isPresent() ? 1 : 0;
    }

    static String summarize_messages(List<ConversationMessage> messages) {
        long user_messages =
                messages.stream().filter(m -> m.role() == MessageRole.USER).count();
        long assistant_messages =
                messages.stream().filter(m -> m.role() == MessageRole.ASSISTANT).count();
        long tool_messages =
                messages.stream().filter(m -> m.role() == MessageRole.TOOL).count();

        Set<String> tool_names_set = new TreeSet<>();
        for (ConversationMessage message : messages) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ContentBlock.ToolUse use) {
                    tool_names_set.add(use.name());
                } else if (block instanceof ContentBlock.ToolResult result) {
                    tool_names_set.add(result.tool_name());
                }
            }
        }
        List<String> tool_names = new ArrayList<>(tool_names_set);

        List<String> lines = new ArrayList<>();
        lines.add("<summary>");
        lines.add("Conversation summary:");
        lines.add(String.format(
                "- Scope: %d earlier messages compacted (user=%d, assistant=%d, tool=%d).",
                messages.size(), user_messages, assistant_messages, tool_messages));

        if (!tool_names.isEmpty()) {
            lines.add("- Tools mentioned: " + String.join(", ", tool_names) + ".");
        }

        List<String> recent_user_requests = collect_recent_role_summaries(messages, MessageRole.USER, 3);
        if (!recent_user_requests.isEmpty()) {
            lines.add("- Recent user requests:");
            for (String request : recent_user_requests) {
                lines.add("  - " + request);
            }
        }

        List<String> pending_work = infer_pending_work(messages);
        if (!pending_work.isEmpty()) {
            lines.add("- Pending work:");
            for (String item : pending_work) {
                lines.add("  - " + item);
            }
        }

        List<String> key_files = collect_key_files(messages);
        if (!key_files.isEmpty()) {
            lines.add("- Key files referenced: " + String.join(", ", key_files) + ".");
        }

        Optional<String> current_work = infer_current_work(messages);
        current_work.ifPresent(value -> lines.add("- Current work: " + value));

        lines.add("- Key timeline:");
        for (ConversationMessage message : messages) {
            String role = role_string(message.role());
            List<String> block_summaries = new ArrayList<>();
            for (ContentBlock block : message.blocks()) {
                block_summaries.add(summarize_block(block));
            }
            lines.add("  - " + role + ": " + String.join(" | ", block_summaries));
        }
        lines.add("</summary>");
        return String.join("\n", lines);
    }

    private static String role_string(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    static String merge_compact_summaries(String existing_summary, String new_summary) {
        if (existing_summary == null) {
            return new_summary;
        }
        List<String> previous_highlights = extract_summary_highlights(existing_summary);
        String new_formatted_summary = format_compact_summary(new_summary);
        List<String> new_highlights = extract_summary_highlights(new_formatted_summary);
        List<String> new_timeline = extract_summary_timeline(new_formatted_summary);

        List<String> lines = new ArrayList<>();
        lines.add("<summary>");
        lines.add("Conversation summary:");

        if (!previous_highlights.isEmpty()) {
            lines.add("- Previously compacted context:");
            for (String line : previous_highlights) {
                lines.add("  " + line);
            }
        }
        if (!new_highlights.isEmpty()) {
            lines.add("- Newly compacted context:");
            for (String line : new_highlights) {
                lines.add("  " + line);
            }
        }
        if (!new_timeline.isEmpty()) {
            lines.add("- Key timeline:");
            for (String line : new_timeline) {
                lines.add("  " + line);
            }
        }
        lines.add("</summary>");
        return String.join("\n", lines);
    }

    static String summarize_block(ContentBlock block) {
        String raw;
        if (block instanceof ContentBlock.Text text) {
            raw = text.text();
        } else if (block instanceof ContentBlock.ToolUse use) {
            raw = "tool_use " + use.name() + "(" + use.input() + ")";
        } else if (block instanceof ContentBlock.ToolResult result) {
            raw = "tool_result " + result.tool_name() + ": " + (result.is_error() ? "error " : "") + result.output();
        } else {
            raw = "";
        }
        return truncate_summary(raw, 160);
    }

    private static List<String> collect_recent_role_summaries(
            List<ConversationMessage> messages, MessageRole role, int limit) {
        List<String> reversed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if (message.role() != role) {
                continue;
            }
            Optional<String> first = first_text_block(message);
            if (first.isPresent()) {
                reversed.add(truncate_summary(first.get(), 160));
                if (reversed.size() >= limit) {
                    break;
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.add(reversed.get(i));
        }
        return result;
    }

    static List<String> infer_pending_work(List<ConversationMessage> messages) {
        List<String> reversed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Optional<String> text = first_text_block(messages.get(i));
            if (text.isEmpty()) {
                continue;
            }
            String lowered = text.get().toLowerCase(Locale.ROOT);
            if (lowered.contains("todo")
                    || lowered.contains("next")
                    || lowered.contains("pending")
                    || lowered.contains("follow up")
                    || lowered.contains("remaining")) {
                reversed.add(truncate_summary(text.get(), 160));
                if (reversed.size() >= 3) {
                    break;
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.add(reversed.get(i));
        }
        return result;
    }

    static List<String> collect_key_files(List<ConversationMessage> messages) {
        Set<String> ordered = new LinkedHashSet<>();
        for (ConversationMessage message : messages) {
            for (ContentBlock block : message.blocks()) {
                String content;
                if (block instanceof ContentBlock.Text text) {
                    content = text.text();
                } else if (block instanceof ContentBlock.ToolUse use) {
                    content = use.input();
                } else if (block instanceof ContentBlock.ToolResult result) {
                    content = result.output();
                } else {
                    content = "";
                }
                ordered.addAll(extract_file_candidates(content));
            }
        }
        List<String> sorted = new ArrayList<>(ordered);
        java.util.Collections.sort(sorted);
        if (sorted.size() > 8) {
            return new ArrayList<>(sorted.subList(0, 8));
        }
        return sorted;
    }

    static Optional<String> infer_current_work(List<ConversationMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Optional<String> text = first_text_block(messages.get(i));
            if (text.isPresent() && !text.get().trim().isEmpty()) {
                return Optional.of(truncate_summary(text.get(), 200));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> first_text_block(ConversationMessage message) {
        for (ContentBlock block : message.blocks()) {
            if (block instanceof ContentBlock.Text text && !text.text().trim().isEmpty()) {
                return Optional.of(text.text());
            }
        }
        return Optional.empty();
    }

    private static boolean has_interesting_extension(String candidate) {
        Path path = Path.of(candidate);
        Path file = path.getFileName();
        if (file == null) {
            return false;
        }
        String name = file.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1);
        for (String expected : new String[] {"rs", "ts", "tsx", "js", "json", "md"}) {
            if (ext.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    static List<String> extract_file_candidates(String content) {
        List<String> result = new ArrayList<>();
        for (String token : content.split("\\s+")) {
            String candidate = trim_punctuation(token);
            if (candidate.contains("/") && has_interesting_extension(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static String trim_punctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && is_trim_char(token.charAt(start))) {
            start += 1;
        }
        while (end > start && is_trim_char(token.charAt(end - 1))) {
            end -= 1;
        }
        return token.substring(start, end);
    }

    private static boolean is_trim_char(char c) {
        return c == ',' || c == '.' || c == ':' || c == ';' || c == ')' || c == '(' || c == '"' || c == '\''
                || c == '`';
    }

    private static String truncate_summary(String content, int max_chars) {
        int char_count = content.codePointCount(0, content.length());
        if (char_count <= max_chars) {
            return content;
        }
        StringBuilder builder = new StringBuilder();
        int taken = 0;
        int i = 0;
        while (i < content.length() && taken < max_chars) {
            int code_point = content.codePointAt(i);
            builder.appendCodePoint(code_point);
            taken += 1;
            i += Character.charCount(code_point);
        }
        builder.append('…');
        return builder.toString();
    }

    static int estimate_message_tokens(ConversationMessage message) {
        int total = 0;
        for (ContentBlock block : message.blocks()) {
            int len;
            if (block instanceof ContentBlock.Text text) {
                len = text.text().length() / 4 + 1;
            } else if (block instanceof ContentBlock.ToolUse use) {
                len = (use.name().length() + use.input().length()) / 4 + 1;
            } else if (block instanceof ContentBlock.ToolResult result) {
                len = (result.tool_name().length() + result.output().length()) / 4 + 1;
            } else {
                len = 0;
            }
            total += len;
        }
        return total;
    }

    static Optional<String> extract_tag_block(String content, String tag) {
        String start = "<" + tag + ">";
        String end = "</" + tag + ">";
        int start_index = content.indexOf(start);
        if (start_index < 0) {
            return Optional.empty();
        }
        start_index += start.length();
        int end_index_rel = content.indexOf(end, start_index);
        if (end_index_rel < 0) {
            return Optional.empty();
        }
        return Optional.of(content.substring(start_index, end_index_rel));
    }

    static String strip_tag_block(String content, String tag) {
        String start = "<" + tag + ">";
        String end = "</" + tag + ">";
        int start_index = content.indexOf(start);
        int end_index_rel = content.indexOf(end);
        if (start_index >= 0 && end_index_rel >= 0) {
            int end_index = end_index_rel + end.length();
            return content.substring(0, start_index) + content.substring(end_index);
        }
        return content;
    }

    static String collapse_blank_lines(String content) {
        StringBuilder result = new StringBuilder();
        boolean last_blank = false;
        // Rust's `lines()` strips the trailing newline; mimic that here.
        String[] lines = content.split("\n", -1);
        int line_count = lines.length;
        // Rust's `str::lines` does not yield a trailing empty entry for content
        // that ends in "\n". So drop a trailing empty string if it exists.
        if (line_count > 0 && lines[line_count - 1].isEmpty()) {
            line_count -= 1;
        }
        for (int i = 0; i < line_count; i++) {
            String line = lines[i];
            boolean is_blank = line.trim().isEmpty();
            if (is_blank && last_blank) {
                continue;
            }
            result.append(line);
            result.append('\n');
            last_blank = is_blank;
        }
        return result.toString();
    }

    static Optional<String> extract_existing_compacted_summary(ConversationMessage message) {
        if (message.role() != MessageRole.SYSTEM) {
            return Optional.empty();
        }
        Optional<String> text = first_text_block(message);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String body = text.get();
        if (!body.startsWith(COMPACT_CONTINUATION_PREAMBLE)) {
            return Optional.empty();
        }
        String summary = body.substring(COMPACT_CONTINUATION_PREAMBLE.length());
        String terminator_a = "\n\n" + COMPACT_RECENT_MESSAGES_NOTE;
        int idx = summary.indexOf(terminator_a);
        if (idx >= 0) {
            summary = summary.substring(0, idx);
        }
        String terminator_b = "\n" + COMPACT_DIRECT_RESUME_INSTRUCTION;
        int idx2 = summary.indexOf(terminator_b);
        if (idx2 >= 0) {
            summary = summary.substring(0, idx2);
        }
        return Optional.of(summary.trim());
    }

    static List<String> extract_summary_highlights(String summary) {
        List<String> lines = new ArrayList<>();
        boolean in_timeline = false;
        for (String raw_line : format_compact_summary(summary).split("\n", -1)) {
            String trimmed = trim_end(raw_line);
            if (trimmed.isEmpty() || trimmed.equals("Summary:") || trimmed.equals("Conversation summary:")) {
                continue;
            }
            if (trimmed.equals("- Key timeline:")) {
                in_timeline = true;
                continue;
            }
            if (in_timeline) {
                continue;
            }
            lines.add(trimmed);
        }
        return lines;
    }

    static List<String> extract_summary_timeline(String summary) {
        List<String> lines = new ArrayList<>();
        boolean in_timeline = false;
        for (String raw_line : format_compact_summary(summary).split("\n", -1)) {
            String trimmed = trim_end(raw_line);
            if (trimmed.equals("- Key timeline:")) {
                in_timeline = true;
                continue;
            }
            if (!in_timeline) {
                continue;
            }
            if (trimmed.isEmpty()) {
                break;
            }
            lines.add(trimmed);
        }
        return lines;
    }

    private static String trim_end(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end -= 1;
        }
        return line.substring(0, end);
    }
}
