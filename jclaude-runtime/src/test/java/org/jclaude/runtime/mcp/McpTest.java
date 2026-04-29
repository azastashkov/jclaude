package org.jclaude.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpTest {

    @Test
    void normalizes_server_names_for_mcp_tooling() {
        assertThat(Mcp.normalize_name_for_mcp("github.com")).isEqualTo("github_com");
        assertThat(Mcp.normalize_name_for_mcp("tool name!")).isEqualTo("tool_name_");
        assertThat(Mcp.normalize_name_for_mcp("claude.ai Example   Server!!")).isEqualTo("claude_ai_Example_Server");
        assertThat(Mcp.mcp_tool_name("claude.ai Example Server", "weather tool"))
                .isEqualTo("mcp__claude_ai_Example_Server__weather_tool");
    }

    @Test
    void unwraps_ccr_proxy_urls_for_signature_matching() {
        String wrapped = "https://api.anthropic.com/v2/session_ingress/shttp/mcp/123"
                + "?mcp_url=https%3A%2F%2Fvendor.example%2Fmcp&other=1";
        assertThat(Mcp.unwrap_ccr_proxy_url(wrapped)).isEqualTo("https://vendor.example/mcp");
        assertThat(Mcp.unwrap_ccr_proxy_url("https://vendor.example/mcp")).isEqualTo("https://vendor.example/mcp");
    }

    @Test
    void mcp_tool_prefix_uses_double_underscore_delimiter() {
        assertThat(Mcp.mcp_tool_prefix("server")).isEqualTo("mcp__server__");
        assertThat(Mcp.mcp_tool_prefix("github.com")).isEqualTo("mcp__github_com__");
    }
}
