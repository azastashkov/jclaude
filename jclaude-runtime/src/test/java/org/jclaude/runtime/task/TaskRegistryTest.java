package org.jclaude.runtime.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskRegistryTest {

    @Test
    void creates_and_retrieves_tasks() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("Do something", "A test task");
        assertThat(task.status()).isEqualTo(TaskStatus.CREATED);
        assertThat(task.prompt()).isEqualTo("Do something");
        assertThat(task.description()).contains("A test task");
        assertThat(task.task_packet()).isEmpty();

        Task fetched = registry.get(task.task_id()).orElseThrow();
        assertThat(fetched.task_id()).isEqualTo(task.task_id());
    }

    @Test
    void creates_task_from_packet() {
        TaskRegistry registry = new TaskRegistry();
        TaskPacket packet = new TaskPacket(
                "Ship task packet support",
                TaskScope.MODULE,
                "runtime/task system",
                "claw-code-parity",
                "/tmp/wt-task",
                "origin/main only",
                List.of("cargo test --workspace"),
                "single commit",
                "print commit sha",
                "manual escalation");

        Task task = registry.create_from_packet(packet);

        assertThat(task.prompt()).isEqualTo(packet.objective());
        assertThat(task.description()).contains("runtime/task system");
        assertThat(task.task_packet()).contains(packet);

        Task fetched = registry.get(task.task_id()).orElseThrow();
        assertThat(fetched.task_packet()).contains(packet);
    }

    @Test
    void lists_tasks_with_optional_filter() {
        TaskRegistry registry = new TaskRegistry();
        registry.create("Task A", null);
        Task task_b = registry.create("Task B", null);
        registry.set_status(task_b.task_id(), TaskStatus.RUNNING);

        List<Task> all = registry.list(Optional.empty());
        assertThat(all).hasSize(2);

        List<Task> running = registry.list(Optional.of(TaskStatus.RUNNING));
        assertThat(running).hasSize(1);
        assertThat(running.get(0).task_id()).isEqualTo(task_b.task_id());

        List<Task> created = registry.list(Optional.of(TaskStatus.CREATED));
        assertThat(created).hasSize(1);
    }

    @Test
    void stops_running_task() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("Stoppable", null);
        registry.set_status(task.task_id(), TaskStatus.RUNNING);

        Task stopped = registry.stop(task.task_id());
        assertThat(stopped.status()).isEqualTo(TaskStatus.STOPPED);

        assertThatThrownBy(() -> registry.stop(task.task_id())).isInstanceOf(TaskRegistryException.class);
    }

    @Test
    void updates_task_with_messages() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("Messageable", null);
        Task updated = registry.update(task.task_id(), "Here's more context");
        assertThat(updated.messages()).hasSize(1);
        assertThat(updated.messages().get(0).content()).isEqualTo("Here's more context");
        assertThat(updated.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    void appends_and_retrieves_output() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("Output task", null);
        registry.append_output(task.task_id(), "line 1\n");
        registry.append_output(task.task_id(), "line 2\n");

        String output = registry.output(task.task_id());
        assertThat(output).isEqualTo("line 1\nline 2\n");
    }

    @Test
    void assigns_team_and_removes_task() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("Team task", null);
        registry.assign_team(task.task_id(), "team_abc");

        Task fetched = registry.get(task.task_id()).orElseThrow();
        assertThat(fetched.team_id()).contains("team_abc");

        Optional<Task> removed = registry.remove(task.task_id());
        assertThat(removed).isPresent();
        assertThat(registry.get(task.task_id())).isEmpty();
        assertThat(registry.is_empty()).isTrue();
    }

    @Test
    void rejects_operations_on_missing_task() {
        TaskRegistry registry = new TaskRegistry();
        assertThatThrownBy(() -> registry.stop("nonexistent")).isInstanceOf(TaskRegistryException.class);
        assertThatThrownBy(() -> registry.update("nonexistent", "msg")).isInstanceOf(TaskRegistryException.class);
        assertThatThrownBy(() -> registry.output("nonexistent")).isInstanceOf(TaskRegistryException.class);
        assertThatThrownBy(() -> registry.append_output("nonexistent", "data"))
                .isInstanceOf(TaskRegistryException.class);
        assertThatThrownBy(() -> registry.set_status("nonexistent", TaskStatus.RUNNING))
                .isInstanceOf(TaskRegistryException.class);
    }

    @Test
    void task_status_display_all_variants() {
        List<TaskStatus> statuses = List.of(
                TaskStatus.CREATED, TaskStatus.RUNNING, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.STOPPED);
        List<String> expected = List.of("created", "running", "completed", "failed", "stopped");

        for (int i = 0; i < statuses.size(); i++) {
            assertThat(statuses.get(i).display()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void stop_rejects_completed_task() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("done", null);
        registry.set_status(task.task_id(), TaskStatus.COMPLETED);

        assertThatThrownBy(() -> registry.stop(task.task_id()))
                .isInstanceOf(TaskRegistryException.class)
                .hasMessageContaining("already in terminal state")
                .hasMessageContaining("completed");
    }

    @Test
    void stop_rejects_failed_task() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("failed", null);
        registry.set_status(task.task_id(), TaskStatus.FAILED);

        assertThatThrownBy(() -> registry.stop(task.task_id()))
                .isInstanceOf(TaskRegistryException.class)
                .hasMessageContaining("already in terminal state")
                .hasMessageContaining("failed");
    }

    @Test
    void stop_succeeds_from_created_state() {
        TaskRegistry registry = new TaskRegistry();
        Task task = registry.create("created task", null);

        Task stopped = registry.stop(task.task_id());

        assertThat(stopped.status()).isEqualTo(TaskStatus.STOPPED);
        assertThat(stopped.updated_at()).isGreaterThanOrEqualTo(task.updated_at());
    }

    @Test
    void new_registry_is_empty() {
        TaskRegistry registry = new TaskRegistry();

        List<Task> all_tasks = registry.list(Optional.empty());

        assertThat(registry.is_empty()).isTrue();
        assertThat(registry.len()).isZero();
        assertThat(all_tasks).isEmpty();
    }

    @Test
    void create_without_description() {
        TaskRegistry registry = new TaskRegistry();

        Task task = registry.create("Do the thing", null);

        assertThat(task.task_id()).startsWith("task_");
        assertThat(task.description()).isEmpty();
        assertThat(task.task_packet()).isEmpty();
        assertThat(task.messages()).isEmpty();
        assertThat(task.output()).isEmpty();
        assertThat(task.team_id()).isEmpty();
    }

    @Test
    void remove_nonexistent_returns_none() {
        TaskRegistry registry = new TaskRegistry();

        Optional<Task> removed = registry.remove("missing");

        assertThat(removed).isEmpty();
    }

    @Test
    void assign_team_rejects_missing_task() {
        TaskRegistry registry = new TaskRegistry();

        assertThatThrownBy(() -> registry.assign_team("missing", "team_123"))
                .isInstanceOf(TaskRegistryException.class)
                .hasMessage("task not found: missing");
    }
}
