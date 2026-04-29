package org.jclaude.cli.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.mockanthropic.CapturedRequest;
import org.jclaude.mockanthropic.MockAnthropicService;
import org.jclaude.mockanthropic.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E mock-parity harness — Java port of the Rust {@code mock_parity_harness.rs}. Boots the in-process
 * {@link MockAnthropicService}, drives the {@code jclaude} CLI through 12 scripted scenarios, and
 * verifies both per-scenario assertions and the captured-request sequence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class MockParityHarnessTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private MockAnthropicService server;
    private List<ScenarioManifestEntry> manifest_entries;

    @BeforeAll
    void start_server_and_load_manifest() throws IOException {
        server = MockAnthropicService.spawn();
        manifest_entries = load_scenario_manifest();

        List<String> manifest_names =
                manifest_entries.stream().map(ScenarioManifestEntry::name).toList();
        assertThat(manifest_names)
                .as("manifest and harness cases must stay aligned")
                .isEqualTo(ScenarioCases.canonical_names());
    }

    @AfterAll
    void stop_server() {
        if (server != null) {
            server.close();
        }
    }

    static Stream<ScenarioCase> scenario_cases() {
        return ScenarioCases.all().stream();
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenario_cases")
    void scenario(ScenarioCase scenario_case, @TempDir Path temp_dir) throws Exception {
        HarnessWorkspace workspace = new HarnessWorkspace(temp_dir);
        workspace.create();
        scenario_case.prepare().accept(workspace);

        ScenarioRun run = HarnessRunner.run(scenario_case, workspace, server.base_url());
        scenario_case.assertion().accept(workspace, run);
    }

    @Order(99)
    @Test
    void verify_captured_request_sequence_matches_rust_harness() {
        List<CapturedRequest> captured = server.captured_requests();
        List<CapturedRequest> messages_only = captured.stream()
                .filter(request -> "/v1/messages".equals(request.path()))
                .toList();
        assertThat(messages_only)
                .as("twelve scenarios should produce twenty-one /v1/messages requests " + "(total captured: "
                        + captured.size() + ", includes count_tokens)")
                .hasSize(21);
        assertThat(messages_only).allMatch(CapturedRequest::stream);

        List<String> scenarios_observed = messages_only.stream()
                .map(request -> request.scenario().map(Scenario::wire_name).orElse(""))
                .toList();
        assertThat(scenarios_observed)
                .containsExactly(
                        "streaming_text",
                        "read_file_roundtrip",
                        "read_file_roundtrip",
                        "grep_chunk_assembly",
                        "grep_chunk_assembly",
                        "write_file_allowed",
                        "write_file_allowed",
                        "write_file_denied",
                        "write_file_denied",
                        "multi_tool_turn_roundtrip",
                        "multi_tool_turn_roundtrip",
                        "bash_stdout_roundtrip",
                        "bash_stdout_roundtrip",
                        "bash_permission_prompt_approved",
                        "bash_permission_prompt_approved",
                        "bash_permission_prompt_denied",
                        "bash_permission_prompt_denied",
                        "plugin_tool_roundtrip",
                        "plugin_tool_roundtrip",
                        "auto_compact_triggered",
                        "token_cost_reporting");
    }

    // ---------------------------------------------------------------- manifest helpers

    private record ScenarioManifestEntry(String name, String category, String description, List<String> parity_refs) {}

    private static List<ScenarioManifestEntry> load_scenario_manifest() throws IOException {
        try (InputStream in = MockParityHarnessTest.class.getResourceAsStream("/mock_parity_scenarios.json")) {
            if (in == null) {
                throw new IllegalStateException("mock_parity_scenarios.json not found on parityTest classpath");
            }
            JsonNode array = MAPPER.readTree(in);
            List<ScenarioManifestEntry> entries = new ArrayList<>();
            for (JsonNode node : array) {
                List<String> parity_refs = new ArrayList<>();
                for (JsonNode ref : node.path("parity_refs")) {
                    parity_refs.add(ref.asText());
                }
                entries.add(new ScenarioManifestEntry(
                        node.path("name").asText(),
                        node.path("category").asText(),
                        node.path("description").asText(),
                        List.copyOf(parity_refs)));
            }
            return List.copyOf(entries);
        }
    }
}
