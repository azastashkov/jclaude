package org.jclaude.mockanthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;
import org.jclaude.api.json.JclaudeMappers;
import org.junit.jupiter.api.Test;

class ScenarioManifestTest {

    @Test
    void manifest_resource_lists_all_twelve_scenarios_in_canonical_order() throws Exception {
        ObjectMapper mapper = JclaudeMappers.standard();
        try (InputStream stream = getClass().getResourceAsStream("/mock_parity_scenarios.json")) {
            assertThat(stream).as("mock_parity_scenarios.json").isNotNull();
            JsonNode array = mapper.readTree(stream);
            assertThat(array.isArray()).isTrue();
            List<String> names = new ArrayList<>();
            StreamSupport.stream(array.spliterator(), false)
                    .forEach(node -> names.add(node.get("name").asText()));
            assertThat(names)
                    .containsExactly(Arrays.stream(Scenario.values())
                            .map(Scenario::wire_name)
                            .toArray(String[]::new));
        }
    }

    @Test
    void every_scenario_in_manifest_resolves_via_from_name() throws Exception {
        ObjectMapper mapper = JclaudeMappers.standard();
        try (InputStream stream = getClass().getResourceAsStream("/mock_parity_scenarios.json")) {
            JsonNode array = mapper.readTree(stream);
            for (JsonNode node : array) {
                String name = node.get("name").asText();
                assertThat(Scenario.from_name(name))
                        .as("scenario %s should resolve", name)
                        .isPresent();
            }
        }
    }
}
