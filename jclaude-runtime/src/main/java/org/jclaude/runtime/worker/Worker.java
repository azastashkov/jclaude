package org.jclaude.runtime.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Mutable worker state. */
public final class Worker {

    private final String worker_id;
    private final String cwd;
    private WorkerStatus status;
    private final boolean trust_auto_resolve;
    private boolean trust_gate_cleared;
    private final boolean auto_recover_prompt_misdelivery;
    private int prompt_delivery_attempts;
    private boolean prompt_in_flight;
    private Optional<String> last_prompt = Optional.empty();
    private Optional<WorkerTaskReceipt> expected_receipt = Optional.empty();
    private Optional<String> replay_prompt = Optional.empty();
    private Optional<WorkerFailure> last_error = Optional.empty();
    private final long created_at;
    private long updated_at;
    private final List<WorkerEvent> events = new ArrayList<>();

    Worker(String worker_id, String cwd, boolean trust_auto_resolve, boolean auto_recover, long ts) {
        this.worker_id = worker_id;
        this.cwd = cwd;
        this.status = WorkerStatus.SPAWNING;
        this.trust_auto_resolve = trust_auto_resolve;
        this.auto_recover_prompt_misdelivery = auto_recover;
        this.created_at = ts;
        this.updated_at = ts;
    }

    public String worker_id() {
        return worker_id;
    }

    public String cwd() {
        return cwd;
    }

    public WorkerStatus status() {
        return status;
    }

    public boolean trust_auto_resolve() {
        return trust_auto_resolve;
    }

    public boolean trust_gate_cleared() {
        return trust_gate_cleared;
    }

    public boolean auto_recover_prompt_misdelivery() {
        return auto_recover_prompt_misdelivery;
    }

    public int prompt_delivery_attempts() {
        return prompt_delivery_attempts;
    }

    public boolean prompt_in_flight() {
        return prompt_in_flight;
    }

    public Optional<String> last_prompt() {
        return last_prompt;
    }

    public Optional<WorkerTaskReceipt> expected_receipt() {
        return expected_receipt;
    }

    public Optional<String> replay_prompt() {
        return replay_prompt;
    }

    public Optional<WorkerFailure> last_error() {
        return last_error;
    }

    public long created_at() {
        return created_at;
    }

    public long updated_at() {
        return updated_at;
    }

    public List<WorkerEvent> events() {
        return List.copyOf(events);
    }

    void set_status(WorkerStatus status) {
        this.status = status;
    }

    void set_trust_gate_cleared(boolean cleared) {
        this.trust_gate_cleared = cleared;
    }

    void set_prompt_in_flight(boolean in_flight) {
        this.prompt_in_flight = in_flight;
    }

    void set_last_prompt(Optional<String> prompt) {
        this.last_prompt = prompt;
    }

    void set_expected_receipt(Optional<WorkerTaskReceipt> receipt) {
        this.expected_receipt = receipt;
    }

    void set_replay_prompt(Optional<String> prompt) {
        this.replay_prompt = prompt;
    }

    void set_last_error(Optional<WorkerFailure> error) {
        this.last_error = error;
    }

    void set_updated_at(long ts) {
        this.updated_at = ts;
    }

    void increment_prompt_attempts() {
        prompt_delivery_attempts++;
    }

    void reset_prompt_attempts() {
        prompt_delivery_attempts = 0;
    }

    void add_event(WorkerEvent event) {
        events.add(event);
    }
}
