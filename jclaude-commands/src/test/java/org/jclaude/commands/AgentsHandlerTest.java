package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsHandlerTest {

    private static void write_agent(Path root, String name, String description, String model, String reasoning)
            throws IOException {
        Files.createDirectories(root);
        Files.writeString(
                root.resolve(name + ".toml"),
                "name = \"" + name + "\"\n" + "description = \"" + description + "\"\n" + "model = \"" + model + "\"\n"
                        + "model_reasoning_effort = \"" + reasoning + "\"\n");
    }

    @Test
    void lists_agents_from_project_and_user_roots(@TempDir Path workspace, @TempDir Path user_home) throws IOException {
        Path project_agents = workspace.resolve(".codex").resolve("agents");
        Path user_agents = user_home.resolve(".claude").resolve("agents");

        write_agent(project_agents, "planner", "Project planner", "gpt-5.4", "medium");
        write_agent(user_agents, "planner", "User planner", "gpt-5.4-mini", "high");
        write_agent(user_agents, "verifier", "Verification agent", "gpt-5.4-mini", "high");

        List<AgentSummary> agents = AgentLoader.load_agents_from_roots(List.of(
                new SimpleEntry<>(DefinitionSource.PROJECT_CODEX, project_agents),
                new SimpleEntry<>(DefinitionSource.USER_CODEX, user_agents)));
        String report = AgentsHandler.render_agents_report(agents);

        assertThat(report).contains("Agents");
        assertThat(report).contains("2 active agents");
        assertThat(report).contains("Project roots:");
        assertThat(report).contains("planner · Project planner · gpt-5.4 · medium");
        assertThat(report).contains("User home roots:");
        assertThat(report).contains("(shadowed by Project roots) planner · User planner");
        assertThat(report).contains("verifier · Verification agent · gpt-5.4-mini · high");
    }

    @Test
    void renders_agents_reports_as_json(@TempDir Path workspace, @TempDir Path user_home) throws IOException {
        Path project_agents = workspace.resolve(".codex").resolve("agents");
        Path user_agents = user_home.resolve(".codex").resolve("agents");

        write_agent(project_agents, "planner", "Project planner", "gpt-5.4", "medium");
        write_agent(project_agents, "verifier", "Verification agent", "gpt-5.4-mini", "high");
        write_agent(user_agents, "planner", "User planner", "gpt-5.4-mini", "high");

        List<AgentSummary> agents = AgentLoader.load_agents_from_roots(List.of(
                new SimpleEntry<>(DefinitionSource.PROJECT_CODEX, project_agents),
                new SimpleEntry<>(DefinitionSource.USER_CODEX, user_agents)));
        JsonNode report = AgentsHandler.render_agents_report_json(workspace, agents);

        assertThat(report.get("kind").asText()).isEqualTo("agents");
        assertThat(report.get("action").asText()).isEqualTo("list");
        assertThat(report.get("working_directory").asText()).isEqualTo(workspace.toString());
        assertThat(report.get("count").asInt()).isEqualTo(3);
        assertThat(report.get("summary").get("active").asInt()).isEqualTo(2);
        assertThat(report.get("summary").get("shadowed").asInt()).isEqualTo(1);
        assertThat(report.get("agents").get(0).get("name").asText()).isEqualTo("planner");
        assertThat(report.get("agents").get(0).get("model").asText()).isEqualTo("gpt-5.4");
        assertThat(report.get("agents").get(0).get("active").asBoolean()).isTrue();
        assertThat(report.get("agents").get(1).get("name").asText()).isEqualTo("verifier");
        assertThat(report.get("agents").get(2).get("name").asText()).isEqualTo("planner");
        assertThat(report.get("agents").get(2).get("active").asBoolean()).isFalse();
        assertThat(report.get("agents").get(2).get("shadowed_by").get("id").asText())
                .isEqualTo("project_claw");

        JsonNode help = AgentsHandler.handle_agents_slash_command_json("help", workspace);
        assertThat(help.get("kind").asText()).isEqualTo("agents");
        assertThat(help.get("action").asText()).isEqualTo("help");
        assertThat(help.get("usage").get("direct_cli").asText()).isEqualTo("claw agents [list|help]");

        JsonNode unexpected = AgentsHandler.handle_agents_slash_command_json("show planner", workspace);
        assertThat(unexpected.get("action").asText()).isEqualTo("help");
        assertThat(unexpected.get("unexpected").asText()).isEqualTo("show planner");
    }
}
