package org.jclaude.runtime.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jclaude.runtime.usage.TokenUsage;

/**
 * Persisted conversational state for the runtime and CLI session manager.
 *
 * <p>{@code workspace_root} binds the session to the worktree it was created
 * in. The global session store under {@code ~/.jclaude/sessions} is shared
 * across every {@code jclaude serve} instance, so without an explicit
 * workspace root parallel lanes can race and report success while writes
 * land in the wrong CWD.
 */
public final class Session {

    static final int SESSION_VERSION = 1;
    static final long ROTATE_AFTER_BYTES = 256 * 1024L;
    static final int MAX_ROTATED_FILES = 3;

    private static final AtomicLong SESSION_ID_COUNTER = new AtomicLong(0);
    private static final AtomicLong LAST_TIMESTAMP_MS = new AtomicLong(0);

    private static final ObjectMapper JSON = SessionJsonMappers.standard();

    private int version;
    private String session_id;
    private long created_at_ms;
    private long updated_at_ms;
    private final List<ConversationMessage> messages;
    private Optional<SessionCompaction> compaction;
    private Optional<SessionFork> fork;
    private Optional<Path> workspace_root;
    private final List<SessionPromptEntry> prompt_history;
    private Optional<Long> last_health_check_ms;
    private Optional<String> model;
    private Optional<Path> persistence;

    private Session() {
        long now = current_time_millis();
        this.version = SESSION_VERSION;
        this.session_id = generate_session_id();
        this.created_at_ms = now;
        this.updated_at_ms = now;
        this.messages = new ArrayList<>();
        this.compaction = Optional.empty();
        this.fork = Optional.empty();
        this.workspace_root = Optional.empty();
        this.prompt_history = new ArrayList<>();
        this.last_health_check_ms = Optional.empty();
        this.model = Optional.empty();
        this.persistence = Optional.empty();
    }

    /** Creates a new empty session with a freshly generated id. */
    public static Session create() {
        return new Session();
    }

    /** Binds this session to {@code path} as its persistence target. */
    public Session with_persistence_path(Path path) {
        this.persistence = Optional.of(path);
        return this;
    }

    /** Binds this session to the workspace root it was created in. */
    public Session with_workspace_root(Path root) {
        this.workspace_root = Optional.of(root);
        return this;
    }

    public int version() {
        return version;
    }

    public String session_id() {
        return session_id;
    }

    public long created_at_ms() {
        return created_at_ms;
    }

    public long updated_at_ms() {
        return updated_at_ms;
    }

    public List<ConversationMessage> messages() {
        return List.copyOf(messages);
    }

    public Optional<SessionCompaction> compaction() {
        return compaction;
    }

    public Optional<SessionFork> fork() {
        return fork;
    }

    public Optional<Path> workspace_root() {
        return workspace_root;
    }

    public List<SessionPromptEntry> prompt_history() {
        return List.copyOf(prompt_history);
    }

    public Optional<Long> last_health_check_ms() {
        return last_health_check_ms;
    }

    public Optional<String> model() {
        return model;
    }

    public Optional<Path> persistence_path() {
        return persistence;
    }

    /** Appends a message to the session and writes it to the JSONL log if persistence is configured. */
    public void append_message(ConversationMessage message) {
        touch();
        messages.add(message);
        try {
            append_persisted_message(message);
        } catch (SessionError error) {
            messages.remove(messages.size() - 1);
            throw error;
        }
    }

    /** Records the current text as a user message and persists it. */
    public void push_user_text(String text) {
        append_message(ConversationMessage.user_text(text));
    }

    /** Records compaction metadata describing the latest rollup of older messages. */
    public void record_compaction(SessionCompaction next) {
        touch();
        int count = compaction.map(existing -> existing.count() + 1).orElse(1);
        this.compaction = Optional.of(new SessionCompaction(count, next.removed_message_count(), next.summary()));
    }

    /**
     * Replaces the in-memory message list with {@code next} and records compaction
     * metadata. Used by auto-compaction to swap older messages out for a synthetic
     * system summary while preserving the recent tail. Persistence is intentionally
     * not rewritten here — callers are expected to manage the JSONL log themselves.
     */
    public void replace_messages_for_compaction(
            List<ConversationMessage> next, String summary, int removed_message_count) {
        touch();
        this.messages.clear();
        this.messages.addAll(next);
        int count = compaction.map(existing -> existing.count() + 1).orElse(1);
        this.compaction = Optional.of(new SessionCompaction(count, removed_message_count, summary));
    }

    /** Records a user prompt with the current wall-clock timestamp. */
    public void add_prompt(String text) {
        SessionPromptEntry entry = new SessionPromptEntry(current_time_millis(), text);
        prompt_history.add(entry);
        append_persisted_prompt_entry(entry);
    }

    /** Marks the time of the last successful health check (ROADMAP #38). */
    public void mark_health_check(long timestamp_ms) {
        this.last_health_check_ms = Optional.of(timestamp_ms);
    }

    /** Sets the model used for this session, persisted so resumed sessions can report it. */
    public void set_model(String model) {
        this.model = Optional.of(model);
    }

    /** Forks this session to produce a new session that retains its history. */
    public Session forked(String branch_name) {
        long now = current_time_millis();
        Session next = new Session();
        next.version = this.version;
        next.session_id = generate_session_id();
        next.created_at_ms = now;
        next.updated_at_ms = now;
        next.messages.addAll(this.messages);
        next.compaction = this.compaction;
        next.fork = Optional.of(new SessionFork(this.session_id, normalize_optional_string(branch_name)));
        next.workspace_root = this.workspace_root;
        next.prompt_history.addAll(this.prompt_history);
        next.last_health_check_ms = this.last_health_check_ms;
        next.model = this.model;
        next.persistence = Optional.empty();
        return next;
    }

    /** Snapshot of this session as a JSONL string. */
    String render_jsonl_snapshot() {
        StringBuilder rendered = new StringBuilder();
        rendered.append(write_json(meta_record())).append('\n');
        if (compaction.isPresent()) {
            rendered.append(write_json(compaction_record(compaction.get()))).append('\n');
        }
        for (SessionPromptEntry entry : prompt_history) {
            rendered.append(write_json(prompt_record(entry))).append('\n');
        }
        for (ConversationMessage message : messages) {
            rendered.append(write_json(message_record(message))).append('\n');
        }
        return rendered.toString();
    }

    /** Saves the current session snapshot to {@code path}, rotating the file if it has grown too large. */
    public void save_to_path(Path path) {
        String snapshot = render_jsonl_snapshot();
        // The snapshot ends with a trailing newline; strip the final '\n' to
        // match the Rust output where lines are joined and then a single '\n'
        // is appended (so the file ends with exactly one newline).
        if (snapshot.endsWith("\n\n")) {
            snapshot = snapshot.substring(0, snapshot.length() - 1);
        }
        rotate_session_file_if_needed(path);
        write_atomic(path, snapshot);
        cleanup_rotated_logs(path);
    }

    /** Loads a session from {@code path}, supporting both legacy JSON and the JSONL log format. */
    public static Session load_from_path(Path path) {
        String contents;
        try {
            contents = Files.readString(path);
        } catch (IOException e) {
            throw SessionError.io(e);
        }

        Session session;
        JsonNode legacy = try_parse_legacy_object(contents);
        if (legacy != null) {
            session = from_json(legacy);
        } else {
            session = from_jsonl(contents);
        }
        return session.with_persistence_path(path);
    }

    /** Appends pending messages to the persistence path used by this session. */
    public void flush() {
        if (persistence.isEmpty()) {
            return;
        }
        save_to_path(persistence.get());
    }

    private static JsonNode try_parse_legacy_object(String contents) {
        try {
            JsonNode node = JSON.readTree(contents);
            if (node != null && node.isObject() && node.has("messages")) {
                return node;
            }
            return null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    /** Reads a legacy single-object session JSON. */
    public static Session from_json(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("session must be an object");
        }
        if (!node.has("messages")) {
            throw SessionError.format("missing messages");
        }
        Session session = new Session();
        if (!node.has("version")) {
            throw SessionError.format("missing version");
        }
        session.version = required_int(node, "version");

        JsonNode messages_node = node.get("messages");
        if (!messages_node.isArray()) {
            throw SessionError.format("missing messages");
        }
        long now = current_time_millis();
        if (node.has("session_id") && node.get("session_id").isTextual()) {
            session.session_id = node.get("session_id").asText();
        }
        if (node.has("created_at_ms")) {
            session.created_at_ms = required_long(node, "created_at_ms");
        } else {
            session.created_at_ms = now;
        }
        if (node.has("updated_at_ms")) {
            session.updated_at_ms = required_long(node, "updated_at_ms");
        } else {
            session.updated_at_ms = session.created_at_ms;
        }
        for (JsonNode entry : messages_node) {
            session.messages.add(parse_message(entry));
        }
        if (node.has("compaction")) {
            session.compaction = Optional.of(parse_compaction(node.get("compaction")));
        }
        if (node.has("fork")) {
            session.fork = Optional.of(parse_fork(node.get("fork")));
        }
        if (node.has("workspace_root") && node.get("workspace_root").isTextual()) {
            session.workspace_root =
                    Optional.of(Paths.get(node.get("workspace_root").asText()));
        }
        if (node.has("prompt_history") && node.get("prompt_history").isArray()) {
            for (JsonNode entry : node.get("prompt_history")) {
                Optional<SessionPromptEntry> parsed = try_parse_prompt_entry(entry);
                parsed.ifPresent(session.prompt_history::add);
            }
        }
        if (node.has("model") && node.get("model").isTextual()) {
            session.model = Optional.of(node.get("model").asText());
        }
        return session;
    }

    private static Session from_jsonl(String contents) {
        Session session = new Session();
        // Reset to placeholder values; meta records will overwrite these.
        // We need to remember whether session_id/created/updated were
        // explicitly supplied so we can apply defaults consistently.
        Long created_supplied = null;
        Long updated_supplied = null;
        String supplied_session_id = null;
        boolean version_seen = false;

        // Clear any defaults from the constructor so we only retain what JSONL provides.
        session.messages.clear();
        session.prompt_history.clear();
        session.compaction = Optional.empty();
        session.fork = Optional.empty();
        session.workspace_root = Optional.empty();
        session.model = Optional.empty();

        String[] lines = contents.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (JsonProcessingException e) {
                throw SessionError.format("invalid JSONL record at line " + (i + 1) + ": " + e.getOriginalMessage());
            }
            if (node == null || !node.isObject()) {
                throw SessionError.format("JSONL record at line " + (i + 1) + " must be an object");
            }
            JsonNode type = node.get("type");
            if (type == null || !type.isTextual()) {
                throw SessionError.format("JSONL record at line " + (i + 1) + " missing type");
            }
            String kind = type.asText();
            switch (kind) {
                case "session_meta" -> {
                    session.version = required_int(node, "version");
                    version_seen = true;
                    supplied_session_id = required_string(node, "session_id");
                    created_supplied = required_long(node, "created_at_ms");
                    updated_supplied = required_long(node, "updated_at_ms");
                    if (node.has("fork")) {
                        session.fork = Optional.of(parse_fork(node.get("fork")));
                    }
                    if (node.has("workspace_root") && node.get("workspace_root").isTextual()) {
                        session.workspace_root =
                                Optional.of(Paths.get(node.get("workspace_root").asText()));
                    }
                    if (node.has("model") && node.get("model").isTextual()) {
                        session.model = Optional.of(node.get("model").asText());
                    }
                }
                case "message" -> {
                    JsonNode message = node.get("message");
                    if (message == null) {
                        throw SessionError.format("JSONL record at line " + (i + 1) + " missing message");
                    }
                    session.messages.add(parse_message(message));
                }
                case "compaction" -> {
                    session.compaction = Optional.of(parse_compaction(node));
                }
                case "prompt_history" -> {
                    Optional<SessionPromptEntry> parsed = try_parse_prompt_entry(node);
                    parsed.ifPresent(session.prompt_history::add);
                }
                default -> throw SessionError.format("unsupported JSONL record type at line " + (i + 1) + ": " + kind);
            }
        }

        if (!version_seen) {
            session.version = SESSION_VERSION;
        }
        long now = current_time_millis();
        session.session_id = supplied_session_id != null ? supplied_session_id : generate_session_id();
        session.created_at_ms = created_supplied != null ? created_supplied : now;
        session.updated_at_ms =
                updated_supplied != null ? updated_supplied : (created_supplied != null ? created_supplied : now);
        return session;
    }

    private static ConversationMessage parse_message(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("message must be an object");
        }
        JsonNode role_node = node.get("role");
        if (role_node == null || !role_node.isTextual()) {
            throw SessionError.format("missing role");
        }
        MessageRole role;
        try {
            role = MessageRole.from_wire(role_node.asText());
        } catch (IllegalArgumentException ex) {
            throw SessionError.format(ex.getMessage());
        }
        JsonNode blocks_node = node.get("blocks");
        if (blocks_node == null || !blocks_node.isArray()) {
            throw SessionError.format("missing blocks");
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode entry : blocks_node) {
            blocks.add(parse_block(entry));
        }
        TokenUsage usage = null;
        if (node.has("usage")) {
            usage = parse_usage(node.get("usage"));
        }
        return new ConversationMessage(role, blocks, usage);
    }

    private static ContentBlock parse_block(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("block must be an object");
        }
        JsonNode type = node.get("type");
        if (type == null || !type.isTextual()) {
            throw SessionError.format("missing block type");
        }
        switch (type.asText()) {
            case "text" -> {
                return new ContentBlock.Text(required_string(node, "text"));
            }
            case "tool_use" -> {
                return new ContentBlock.ToolUse(
                        required_string(node, "id"), required_string(node, "name"), required_string(node, "input"));
            }
            case "tool_result" -> {
                JsonNode err = node.get("is_error");
                if (err == null || !err.isBoolean()) {
                    throw SessionError.format("missing is_error");
                }
                return new ContentBlock.ToolResult(
                        required_string(node, "tool_use_id"),
                        required_string(node, "tool_name"),
                        required_string(node, "output"),
                        err.asBoolean());
            }
            default -> throw SessionError.format("unsupported block type: " + type.asText());
        }
    }

    private static TokenUsage parse_usage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("usage must be an object");
        }
        return new TokenUsage(
                required_int(node, "input_tokens"),
                required_int(node, "output_tokens"),
                required_int(node, "cache_creation_input_tokens"),
                required_int(node, "cache_read_input_tokens"));
    }

    private static SessionCompaction parse_compaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("compaction must be an object");
        }
        return new SessionCompaction(
                required_int(node, "count"),
                required_int(node, "removed_message_count"),
                required_string(node, "summary"));
    }

    private static SessionFork parse_fork(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw SessionError.format("fork metadata must be an object");
        }
        String parent = required_string(node, "parent_session_id");
        String branch = null;
        if (node.has("branch_name") && node.get("branch_name").isTextual()) {
            branch = node.get("branch_name").asText();
        }
        return new SessionFork(parent, branch);
    }

    private static Optional<SessionPromptEntry> try_parse_prompt_entry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode timestamp = node.get("timestamp_ms");
        JsonNode text = node.get("text");
        if (timestamp == null || !timestamp.canConvertToLong()) {
            return Optional.empty();
        }
        if (text == null || !text.isTextual()) {
            return Optional.empty();
        }
        long ts = timestamp.asLong();
        if (ts < 0) {
            return Optional.empty();
        }
        return Optional.of(new SessionPromptEntry(ts, text.asText()));
    }

    private void append_persisted_message(ConversationMessage message) {
        if (persistence.isEmpty()) {
            return;
        }
        Path path = persistence.get();
        if (needs_bootstrap(path)) {
            save_to_path(path);
            return;
        }
        try {
            String line = write_json(message_record(message)) + "\n";
            Files.writeString(path, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    private void append_persisted_prompt_entry(SessionPromptEntry entry) {
        if (persistence.isEmpty()) {
            return;
        }
        Path path = persistence.get();
        if (needs_bootstrap(path)) {
            save_to_path(path);
            return;
        }
        try {
            String line = write_json(prompt_record(entry)) + "\n";
            Files.writeString(path, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    private static boolean needs_bootstrap(Path path) {
        try {
            return !Files.exists(path) || Files.size(path) == 0;
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    private ObjectNode meta_record() {
        ObjectNode object = JSON.createObjectNode();
        object.put("type", "session_meta");
        object.put("version", version);
        object.put("session_id", session_id);
        object.put("created_at_ms", created_at_ms);
        object.put("updated_at_ms", updated_at_ms);
        if (fork.isPresent()) {
            object.set("fork", fork_to_node(fork.get()));
        }
        if (workspace_root.isPresent()) {
            object.put("workspace_root", workspace_root_to_string(workspace_root.get()));
        }
        if (model.isPresent()) {
            object.put("model", model.get());
        }
        return object;
    }

    private ObjectNode compaction_record(SessionCompaction value) {
        ObjectNode object = JSON.createObjectNode();
        object.put("type", "compaction");
        object.put("count", value.count());
        object.put("removed_message_count", value.removed_message_count());
        object.put("summary", value.summary());
        return object;
    }

    private ObjectNode prompt_record(SessionPromptEntry entry) {
        ObjectNode object = JSON.createObjectNode();
        object.put("type", "prompt_history");
        object.put("timestamp_ms", entry.timestamp_ms());
        object.put("text", entry.text());
        return object;
    }

    private ObjectNode message_record(ConversationMessage message) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("type", "message");
        envelope.set("message", message_to_node(message));
        return envelope;
    }

    private ObjectNode message_to_node(ConversationMessage message) {
        ObjectNode object = JSON.createObjectNode();
        object.put("role", message.role().wire());
        var blocks_array = object.putArray("blocks");
        for (ContentBlock block : message.blocks()) {
            blocks_array.add(block_to_node(block));
        }
        if (message.usage() != null) {
            object.set("usage", usage_to_node(message.usage()));
        }
        return object;
    }

    private ObjectNode block_to_node(ContentBlock block) {
        ObjectNode object = JSON.createObjectNode();
        if (block instanceof ContentBlock.Text text) {
            object.put("type", "text");
            object.put("text", text.text());
        } else if (block instanceof ContentBlock.ToolUse use) {
            object.put("type", "tool_use");
            object.put("id", use.id());
            object.put("name", use.name());
            object.put("input", use.input());
        } else if (block instanceof ContentBlock.ToolResult result) {
            object.put("type", "tool_result");
            object.put("tool_use_id", result.tool_use_id());
            object.put("tool_name", result.tool_name());
            object.put("output", result.output());
            object.put("is_error", result.is_error());
        }
        return object;
    }

    private ObjectNode usage_to_node(TokenUsage usage) {
        ObjectNode object = JSON.createObjectNode();
        object.put("input_tokens", usage.input_tokens());
        object.put("output_tokens", usage.output_tokens());
        object.put("cache_creation_input_tokens", usage.cache_creation_input_tokens());
        object.put("cache_read_input_tokens", usage.cache_read_input_tokens());
        return object;
    }

    private ObjectNode fork_to_node(SessionFork value) {
        ObjectNode object = JSON.createObjectNode();
        object.put("parent_session_id", value.parent_session_id());
        if (value.branch_name() != null) {
            object.put("branch_name", value.branch_name());
        }
        return object;
    }

    private static String write_json(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw SessionError.json(e);
        }
    }

    private static String required_string(JsonNode node, String key) {
        JsonNode child = node.get(key);
        if (child == null || !child.isTextual()) {
            throw SessionError.format("missing " + key);
        }
        return child.asText();
    }

    private static int required_int(JsonNode node, String key) {
        JsonNode child = node.get(key);
        if (child == null || !child.isIntegralNumber()) {
            throw SessionError.format("missing " + key);
        }
        long value = child.asLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw SessionError.format(key + " out of range");
        }
        return (int) value;
    }

    private static long required_long(JsonNode node, String key) {
        JsonNode child = node.get(key);
        if (child == null || !child.isIntegralNumber()) {
            throw SessionError.format("missing " + key);
        }
        return child.asLong();
    }

    private void touch() {
        this.updated_at_ms = current_time_millis();
    }

    /** Returns a monotonic wall-clock timestamp in milliseconds. */
    public static long current_time_millis() {
        long wall_clock = Math.max(0, Instant.now().toEpochMilli());
        long candidate = wall_clock;
        while (true) {
            long previous = LAST_TIMESTAMP_MS.get();
            if (candidate <= previous) {
                candidate = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1;
            }
            if (LAST_TIMESTAMP_MS.compareAndSet(previous, candidate)) {
                return candidate;
            }
            long actual = LAST_TIMESTAMP_MS.get();
            candidate = actual == Long.MAX_VALUE ? Long.MAX_VALUE : actual + 1;
        }
    }

    /** Generates a unique session id using the monotonic clock and a counter. */
    public static String generate_session_id() {
        long millis = current_time_millis();
        long counter = SESSION_ID_COUNTER.getAndIncrement();
        return "session-" + millis + "-" + counter;
    }

    private static String workspace_root_to_string(Path path) {
        String text = path.toString();
        if (text == null) {
            throw SessionError.format("workspace_root is not valid UTF-8: " + path);
        }
        return text;
    }

    private static String normalize_optional_string(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void write_atomic(Path path, String contents) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = temporary_path_for(path);
            Files.writeString(temp, contents);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    private static Path temporary_path_for(Path path) {
        Path nameOnly = path.getFileName();
        String file_name = nameOnly == null ? "session" : nameOnly.toString();
        long millis = current_time_millis();
        long counter = SESSION_ID_COUNTER.getAndIncrement();
        Path parent = path.getParent();
        String tempName = file_name + ".tmp-" + millis + "-" + counter;
        return parent == null ? Paths.get(tempName) : parent.resolve(tempName);
    }

    static void rotate_session_file_if_needed(Path path) {
        long size;
        try {
            if (!Files.exists(path)) {
                return;
            }
            size = Files.size(path);
        } catch (IOException e) {
            // Mirror Rust: missing/unreadable metadata silently skips rotation.
            return;
        }
        if (size < ROTATE_AFTER_BYTES) {
            return;
        }
        Path rotated = rotated_log_path(path);
        try {
            Files.move(path, rotated);
        } catch (IOException e) {
            throw SessionError.io(e);
        }
    }

    static Path rotated_log_path(Path path) {
        Path nameOnly = path.getFileName();
        String stem = nameOnly == null ? "session" : strip_extension(nameOnly.toString());
        Path parent = path.getParent();
        String rotatedName = stem + ".rot-" + current_time_millis() + ".jsonl";
        return parent == null ? Paths.get(rotatedName) : parent.resolve(rotatedName);
    }

    static void cleanup_rotated_logs(Path path) {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        Path nameOnly = path.getFileName();
        String stem = nameOnly == null ? "session" : strip_extension(nameOnly.toString());
        String prefix = stem + ".rot-";

        List<Path> rotated;
        try (Stream<Path> stream = Files.list(parent)) {
            rotated = stream.filter(entry -> {
                        Path name = entry.getFileName();
                        if (name == null) {
                            return false;
                        }
                        String n = name.toString();
                        if (!n.startsWith(prefix)) {
                            return false;
                        }
                        int dot = n.lastIndexOf('.');
                        if (dot < 0) {
                            return false;
                        }
                        String ext = n.substring(dot + 1);
                        return ext.toLowerCase(Locale.ROOT).equals("jsonl");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw SessionError.io(e);
        }

        rotated.sort(Comparator.comparing(Session::last_modified_safely));

        int remove_count = Math.max(0, rotated.size() - MAX_ROTATED_FILES);
        for (int i = 0; i < remove_count; i++) {
            try {
                Files.deleteIfExists(rotated.get(i));
            } catch (IOException e) {
                throw SessionError.io(e);
            }
        }
    }

    private static FileTime last_modified_safely(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static String strip_extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Session other)) {
            return false;
        }
        return version == other.version
                && created_at_ms == other.created_at_ms
                && updated_at_ms == other.updated_at_ms
                && session_id.equals(other.session_id)
                && messages.equals(other.messages)
                && compaction.equals(other.compaction)
                && fork.equals(other.fork)
                && workspace_root.equals(other.workspace_root)
                && prompt_history.equals(other.prompt_history)
                && last_health_check_ms.equals(other.last_health_check_ms);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(version);
        result = 31 * result + session_id.hashCode();
        result = 31 * result + Long.hashCode(created_at_ms);
        result = 31 * result + Long.hashCode(updated_at_ms);
        result = 31 * result + messages.hashCode();
        result = 31 * result + compaction.hashCode();
        result = 31 * result + fork.hashCode();
        result = 31 * result + workspace_root.hashCode();
        result = 31 * result + prompt_history.hashCode();
        result = 31 * result + last_health_check_ms.hashCode();
        return result;
    }
}
