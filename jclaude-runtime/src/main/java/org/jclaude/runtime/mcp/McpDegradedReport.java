package org.jclaude.runtime.mcp;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Report describing a degraded startup where at least one server succeeded and at least one
 * failed. Mirrors the Rust {@code McpDegradedReport} record.
 */
public record McpDegradedReport(
        List<String> working_servers,
        List<McpFailedServer> failed_servers,
        List<String> available_tools,
        List<String> missing_tools) {

    public McpDegradedReport {
        Objects.requireNonNull(working_servers, "working_servers");
        Objects.requireNonNull(failed_servers, "failed_servers");
        Objects.requireNonNull(available_tools, "available_tools");
        Objects.requireNonNull(missing_tools, "missing_tools");
        working_servers = List.copyOf(working_servers);
        failed_servers = List.copyOf(failed_servers);
        available_tools = List.copyOf(available_tools);
        missing_tools = List.copyOf(missing_tools);
    }

    /**
     * Construct a report, deduplicating and sorting working/available/expected sets, and computing
     * the missing-tool delta from {@code expected_tools} minus {@code available_tools}.
     */
    public static McpDegradedReport create(
            List<String> working_servers,
            List<McpFailedServer> failed_servers,
            List<String> available_tools,
            List<String> expected_tools) {
        List<String> working_sorted = dedupeSorted(working_servers);
        List<String> available_sorted = dedupeSorted(available_tools);
        var available_set = new TreeSet<>(available_sorted);
        List<String> expected_sorted = dedupeSorted(expected_tools);
        List<String> missing = expected_sorted.stream()
                .filter(tool -> !available_set.contains(tool))
                .toList();
        return new McpDegradedReport(working_sorted, List.copyOf(failed_servers), available_sorted, missing);
    }

    private static List<String> dedupeSorted(List<String> values) {
        return values.stream().distinct().sorted().toList();
    }
}
