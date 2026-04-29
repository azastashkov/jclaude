package org.jclaude.runtime.task;

import java.util.ArrayList;
import java.util.List;

/** Validation entry point for {@link TaskPacket} values. */
public final class TaskPackets {

    private TaskPackets() {}

    public static ValidatedPacket validate(TaskPacket packet) {
        List<String> errors = new ArrayList<>();

        validate_required("objective", packet.objective(), errors);
        validate_required("repo", packet.repo(), errors);
        validate_required("branch_policy", packet.branch_policy(), errors);
        validate_required("commit_policy", packet.commit_policy(), errors);
        validate_required("reporting_contract", packet.reporting_contract(), errors);
        validate_required("escalation_policy", packet.escalation_policy(), errors);

        validate_scope_requirements(packet, errors);

        List<String> tests = packet.acceptance_tests();
        for (int index = 0; index < tests.size(); index++) {
            String test = tests.get(index);
            if (test == null || test.trim().isEmpty()) {
                errors.add("acceptance_tests contains an empty value at index " + index);
            }
        }

        if (errors.isEmpty()) {
            return new ValidatedPacket(packet);
        }
        throw new TaskPacketValidationError(errors);
    }

    private static void validate_scope_requirements(TaskPacket packet, List<String> errors) {
        boolean needs_scope_path = packet.scope() == TaskScope.MODULE
                || packet.scope() == TaskScope.SINGLE_FILE
                || packet.scope() == TaskScope.CUSTOM;
        if (needs_scope_path) {
            String path = packet.scope_path();
            if (path == null || path.trim().isEmpty()) {
                errors.add("scope_path is required for scope '" + packet.scope().display() + "'");
            }
        }
    }

    private static void validate_required(String field, String value, List<String> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(field + " must not be empty");
        }
    }
}
