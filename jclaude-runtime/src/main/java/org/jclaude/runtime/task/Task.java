package org.jclaude.runtime.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** In-memory representation of a sub-agent task. */
public final class Task {

    private final String task_id;
    private String prompt;
    private String description;
    private TaskPacket task_packet;
    private TaskStatus status;
    private final long created_at;
    private long updated_at;
    private final List<TaskMessage> messages;
    private String output;
    private String team_id;

    Task(
            String task_id,
            String prompt,
            String description,
            TaskPacket task_packet,
            TaskStatus status,
            long created_at,
            long updated_at) {
        this.task_id = task_id;
        this.prompt = prompt;
        this.description = description;
        this.task_packet = task_packet;
        this.status = status;
        this.created_at = created_at;
        this.updated_at = updated_at;
        this.messages = new ArrayList<>();
        this.output = "";
        this.team_id = null;
    }

    private Task(Task other) {
        this.task_id = other.task_id;
        this.prompt = other.prompt;
        this.description = other.description;
        this.task_packet = other.task_packet;
        this.status = other.status;
        this.created_at = other.created_at;
        this.updated_at = other.updated_at;
        this.messages = new ArrayList<>(other.messages);
        this.output = other.output;
        this.team_id = other.team_id;
    }

    /** Returns a defensive snapshot of this task. */
    public Task snapshot() {
        return new Task(this);
    }

    @JsonProperty("task_id")
    public String task_id() {
        return task_id;
    }

    @JsonProperty("prompt")
    public String prompt() {
        return prompt;
    }

    @JsonProperty("description")
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    @JsonProperty("task_packet")
    public Optional<TaskPacket> task_packet() {
        return Optional.ofNullable(task_packet);
    }

    @JsonProperty("status")
    public TaskStatus status() {
        return status;
    }

    @JsonProperty("created_at")
    public long created_at() {
        return created_at;
    }

    @JsonProperty("updated_at")
    public long updated_at() {
        return updated_at;
    }

    @JsonProperty("messages")
    public List<TaskMessage> messages() {
        return List.copyOf(messages);
    }

    @JsonProperty("output")
    public String output() {
        return output;
    }

    @JsonProperty("team_id")
    public Optional<String> team_id() {
        return Optional.ofNullable(team_id);
    }

    void set_status_internal(TaskStatus status) {
        this.status = status;
    }

    void set_updated_at_internal(long updated_at) {
        this.updated_at = updated_at;
    }

    void add_message_internal(TaskMessage message) {
        this.messages.add(message);
    }

    void append_output_internal(String output) {
        this.output = this.output + output;
    }

    void set_team_internal(String team_id) {
        this.team_id = team_id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Task other)) {
            return false;
        }
        return created_at == other.created_at
                && updated_at == other.updated_at
                && task_id.equals(other.task_id)
                && Objects.equals(prompt, other.prompt)
                && Objects.equals(description, other.description)
                && Objects.equals(task_packet, other.task_packet)
                && status == other.status
                && messages.equals(other.messages)
                && Objects.equals(output, other.output)
                && Objects.equals(team_id, other.team_id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(task_id, prompt, description, task_packet, status, messages, output, team_id);
    }
}
