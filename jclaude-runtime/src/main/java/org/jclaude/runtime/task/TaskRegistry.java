package org.jclaude.runtime.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory registry tracking sub-agent task lifecycle. Mirrors the Rust {@code TaskRegistry}. */
public final class TaskRegistry {

    private final Object lock = new Object();
    private final Map<String, Task> tasks = new HashMap<>();
    private long counter;

    public TaskRegistry() {}

    /** Creates a task with the supplied prompt and optional description. */
    public Task create(String prompt, String description) {
        return create_task(prompt, description, null);
    }

    /** Creates a task backed by a validated {@link TaskPacket}. */
    public Task create_from_packet(TaskPacket packet) {
        TaskPacket validated = TaskPackets.validate(packet).into_inner();
        String description = validated.scope_path() != null
                ? validated.scope_path()
                : validated.scope().display();
        return create_task(validated.objective(), description, validated);
    }

    private Task create_task(String prompt, String description, TaskPacket task_packet) {
        synchronized (lock) {
            counter += 1;
            long ts = now_secs();
            String task_id = String.format("task_%08x_%d", ts, counter);
            Task task = new Task(task_id, prompt, description, task_packet, TaskStatus.CREATED, ts, ts);
            tasks.put(task_id, task);
            return task.snapshot();
        }
    }

    public Optional<Task> get(String task_id) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            return task == null ? Optional.empty() : Optional.of(task.snapshot());
        }
    }

    /** Lists tasks, optionally filtering by status. */
    public List<Task> list(Optional<TaskStatus> status_filter) {
        synchronized (lock) {
            List<Task> result = new ArrayList<>();
            for (Task task : tasks.values()) {
                if (status_filter.isEmpty() || task.status() == status_filter.get()) {
                    result.add(task.snapshot());
                }
            }
            return result;
        }
    }

    /** Stops the task. Returns the updated snapshot or throws when the task is missing or terminal. */
    public Task stop(String task_id) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            switch (task.status()) {
                case COMPLETED, FAILED, STOPPED -> throw new TaskRegistryException("task " + task_id
                        + " is already in terminal state: " + task.status().display());
                default -> {}
            }
            task.set_status_internal(TaskStatus.STOPPED);
            task.set_updated_at_internal(now_secs());
            return task.snapshot();
        }
    }

    /** Records a user message on the task and returns the updated snapshot. */
    public Task update(String task_id, String message) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            long ts = now_secs();
            task.add_message_internal(new TaskMessage("user", message, ts));
            task.set_updated_at_internal(ts);
            return task.snapshot();
        }
    }

    public String output(String task_id) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            return task.output();
        }
    }

    public void append_output(String task_id, String output) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            task.append_output_internal(output);
            task.set_updated_at_internal(now_secs());
        }
    }

    public void set_status(String task_id, TaskStatus status) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            task.set_status_internal(status);
            task.set_updated_at_internal(now_secs());
        }
    }

    public void assign_team(String task_id, String team_id) {
        synchronized (lock) {
            Task task = tasks.get(task_id);
            if (task == null) {
                throw new TaskRegistryException("task not found: " + task_id);
            }
            task.set_team_internal(team_id);
            task.set_updated_at_internal(now_secs());
        }
    }

    public Optional<Task> remove(String task_id) {
        synchronized (lock) {
            Task removed = tasks.remove(task_id);
            return removed == null ? Optional.empty() : Optional.of(removed.snapshot());
        }
    }

    public int len() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    public boolean is_empty() {
        return len() == 0;
    }

    static long now_secs() {
        return Instant.now().getEpochSecond();
    }
}
