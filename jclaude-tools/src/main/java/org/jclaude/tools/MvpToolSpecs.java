package org.jclaude.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.tools.bridge.McpToolBridgeAdapter;

/**
 * Catalogue of tool specs that the dispatcher can route. Mirrors the upstream Rust
 * {@code mvp_tool_specs()} function in {@code claw-code/rust/crates/tools/src/lib.rs}
 * (around L392) and is extended with a small set of harness-side deferred specs (Brief alias,
 * EnterWorktree/ExitWorktree/Monitor placeholders, common {@code mcp__claude_ai_*} authentication
 * stubs) so the {@link ToolSearch} surface still resolves them.
 *
 * <p>Phase 1 only shipped the 13-item MVP slice via {@link #mvp_tool_specs()}. Phase 3 keeps that
 * method intact for backward compatibility and adds {@link #all_tool_specs()} which returns the
 * full surface area the dispatcher routes today.
 */
public final class MvpToolSpecs {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private MvpToolSpecs() {}

    /**
     * Returns the original 13 Phase-1 MVP specs. Kept as-is for backward compatibility with the
     * {@code WireRunner} bootstrap, the parity harness, and the Phase 1 tests.
     */
    public static List<ToolSpec> mvp_tool_specs() {
        return List.of(
                spec("read_file", "Read a text file from the workspace.", READ_FILE_SCHEMA),
                spec("write_file", "Write a text file in the workspace.", WRITE_FILE_SCHEMA),
                spec("edit_file", "Replace text in a workspace file.", EDIT_FILE_SCHEMA),
                spec("glob_search", "Find files by glob pattern.", GLOB_SEARCH_SCHEMA),
                spec("grep_search", "Search file contents with a regex pattern.", GREP_SEARCH_SCHEMA),
                spec("bash", "Execute a shell command in the current workspace.", BASH_SCHEMA),
                spec("TodoWrite", "Update the structured task list for the current session.", TODO_WRITE_SCHEMA),
                spec("Sleep", "Wait for a specified duration without holding a shell process.", SLEEP_SCHEMA),
                spec(
                        "ToolSearch",
                        "Search for deferred or specialized tools by exact name or keywords.",
                        TOOL_SEARCH_SCHEMA),
                spec("StructuredOutput", "Return structured output in the requested format.", STRUCTURED_OUTPUT_SCHEMA),
                spec("EnterPlanMode", "Enable a worktree-local planning mode override.", PLAN_MODE_SCHEMA),
                spec("ExitPlanMode", "Restore or clear the worktree-local planning mode override.", PLAN_MODE_SCHEMA),
                spec("SendUserMessage", "Send a message to the user.", SEND_USER_MESSAGE_SCHEMA));
    }

    /**
     * Returns the union of {@link #all_tool_specs()} and any MCP-tool specs sourced from the
     * supplied {@link McpToolBridgeAdapter}. When the optional is empty this is identical to
     * {@code all_tool_specs()}; when present, dynamically-discovered MCP tools are appended to the
     * end so the model can resolve them through {@code ToolSearch} and dispatch them through the
     * runtime bridge.
     */
    public static List<ToolSpec> all_tool_specs(Optional<McpToolBridgeAdapter> mcp_bridge) {
        List<ToolSpec> base = new ArrayList<>(all_tool_specs());
        if (mcp_bridge != null && mcp_bridge.isPresent()) {
            for (ToolSpec extra : mcp_bridge.get().tool_specs()) {
                base.add(extra);
            }
        }
        return List.copyOf(base);
    }

    /**
     * Returns the complete set of tool specs the Phase-3 dispatcher knows how to route. Includes
     * the MVP slice, the Rust catalogue, and a handful of harness-side stubs whose schemas mirror
     * the deferred-tool surface area so the model can discover them via {@link ToolSearch}.
     */
    public static List<ToolSpec> all_tool_specs() {
        List<ToolSpec> all = new ArrayList<>();
        all.addAll(mvp_tool_specs());

        // Read-only tools beyond the MVP slice.
        all.add(spec(
                "WebFetch",
                "Fetch a URL, convert it into readable text, and answer a prompt about it.",
                WEB_FETCH_SCHEMA));
        all.add(spec(
                "WebSearch", "Search the web for current information and return cited results.", WEB_SEARCH_SCHEMA));
        all.add(spec("Skill", "Load a local skill definition and its instructions.", SKILL_SCHEMA));
        all.add(spec("Agent", "Launch a specialized agent task and persist its handoff metadata.", AGENT_SCHEMA));
        all.add(spec("NotebookEdit", "Replace, insert, or delete a cell in a Jupyter notebook.", NOTEBOOK_EDIT_SCHEMA));

        // SendUserMessage alias kept for parity with Rust's "Brief" branch.
        all.add(spec(
                "Brief", "Send a brief message to the user (alias of SendUserMessage).", SEND_USER_MESSAGE_SCHEMA));

        // Subprocess execution.
        all.add(spec("REPL", "Execute code in a REPL-like subprocess.", REPL_SCHEMA));
        all.add(spec("PowerShell", "Execute a PowerShell command with optional timeout.", POWERSHELL_SCHEMA));

        // Config / interactive.
        all.add(spec("Config", "Get or set Claude Code settings.", CONFIG_SCHEMA));
        all.add(spec(
                "AskUserQuestion", "Ask the user a question and wait for their response.", ASK_USER_QUESTION_SCHEMA));

        // Task registry tools.
        all.add(spec("TaskCreate", "Create a background task that runs in a separate subprocess.", TASK_CREATE_SCHEMA));
        all.add(spec(
                "RunTaskPacket", "Create a background task from a structured task packet.", RUN_TASK_PACKET_SCHEMA));
        all.add(spec("TaskGet", "Get the status and details of a background task by ID.", TASK_ID_SCHEMA));
        all.add(spec("TaskList", "List all background tasks and their current status.", EMPTY_OBJECT_SCHEMA));
        all.add(spec("TaskStop", "Stop a running background task by ID.", TASK_ID_SCHEMA));
        all.add(spec("TaskUpdate", "Send a message or update to a running background task.", TASK_UPDATE_SCHEMA));
        all.add(spec("TaskOutput", "Retrieve the output produced by a background task.", TASK_ID_SCHEMA));

        // Worker registry tools (Phase-3 stubs — Worker registry not yet ported).
        all.add(spec("WorkerCreate", "Create a coding worker boot session.", WORKER_CREATE_SCHEMA));
        all.add(spec("WorkerGet", "Fetch the current worker boot state.", WORKER_ID_SCHEMA));
        all.add(spec("WorkerObserve", "Feed a terminal snapshot into worker boot detection.", WORKER_OBSERVE_SCHEMA));
        all.add(spec(
                "WorkerResolveTrust",
                "Resolve a detected trust prompt so worker boot can continue.",
                WORKER_ID_SCHEMA));
        all.add(spec("WorkerAwaitReady", "Return the current ready-handshake verdict for a worker.", WORKER_ID_SCHEMA));
        all.add(spec(
                "WorkerSendPrompt",
                "Send a task prompt only after the worker reaches ready_for_prompt.",
                WORKER_SEND_PROMPT_SCHEMA));
        all.add(spec("WorkerRestart", "Restart worker boot state after a failed startup.", WORKER_ID_SCHEMA));
        all.add(spec("WorkerTerminate", "Terminate a worker and mark the lane finished.", WORKER_ID_SCHEMA));
        all.add(spec(
                "WorkerObserveCompletion",
                "Report session completion to the worker.",
                WORKER_OBSERVE_COMPLETION_SCHEMA));

        // Team / Cron tools.
        all.add(spec("TeamCreate", "Create a team of sub-agents for parallel task execution.", TEAM_CREATE_SCHEMA));
        all.add(spec("TeamDelete", "Delete a team and stop all its running tasks.", TEAM_DELETE_SCHEMA));
        all.add(spec("CronCreate", "Create a scheduled recurring task.", CRON_CREATE_SCHEMA));
        all.add(spec("CronDelete", "Delete a scheduled recurring task by ID.", CRON_DELETE_SCHEMA));
        all.add(spec("CronList", "List all scheduled recurring tasks.", EMPTY_OBJECT_SCHEMA));

        // LSP.
        all.add(spec("LSP", "Query Language Server Protocol for code intelligence.", LSP_SCHEMA));

        // MCP family.
        all.add(spec(
                "ListMcpResources", "List available resources from connected MCP servers.", LIST_MCP_RESOURCES_SCHEMA));
        all.add(spec(
                "ReadMcpResource", "Read a specific resource from an MCP server by URI.", READ_MCP_RESOURCE_SCHEMA));
        all.add(spec(
                "McpAuth", "Authenticate with an MCP server that requires OAuth or credentials.", MCP_AUTH_SCHEMA));
        all.add(spec("RemoteTrigger", "Trigger a remote action or webhook endpoint.", REMOTE_TRIGGER_SCHEMA));
        all.add(spec("MCP", "Execute a tool provided by a connected MCP server.", MCP_SCHEMA));

        // Test-only tool.
        all.add(spec(
                "TestingPermission",
                "Test-only tool for verifying permission enforcement behavior.",
                TESTING_PERMISSION_SCHEMA));

        // Harness-side deferred tools surfaced for ToolSearch parity. These return
        // canonical "not yet implemented" errors when invoked.
        all.add(spec("EnterWorktree", "Enter a worktree-scoped session (deferred — Phase 4).", EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "ExitWorktree", "Exit the active worktree-scoped session (deferred — Phase 4).", EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "Monitor", "Stream notifications from a backgrounded process (deferred — Phase 4).", MONITOR_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Gmail__authenticate",
                "Begin an MCP Gmail authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Gmail__complete_authentication",
                "Complete an MCP Gmail authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Google_Calendar__authenticate",
                "Begin an MCP Google Calendar authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Google_Calendar__complete_authentication",
                "Complete an MCP Google Calendar authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Google_Drive__authenticate",
                "Begin an MCP Google Drive authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));
        all.add(spec(
                "mcp__claude_ai_Google_Drive__complete_authentication",
                "Complete an MCP Google Drive authentication flow (deferred — Phase 4).",
                EMPTY_OBJECT_SCHEMA));

        return List.copyOf(all);
    }

    private static ToolSpec spec(String name, String description, String schema_json) {
        return new ToolSpec(name, description, parse(schema_json));
    }

    private static JsonNode parse(String schema_json) {
        try {
            return MAPPER.readTree(schema_json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid embedded JSON schema: " + schema_json, e);
        }
    }

    private static final String READ_FILE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "offset": { "type": "integer", "minimum": 0 },
                "limit": { "type": "integer", "minimum": 1 }
              },
              "required": ["path"],
              "additionalProperties": false
            }
            """;

    private static final String WRITE_FILE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "content": { "type": "string" }
              },
              "required": ["path", "content"],
              "additionalProperties": false
            }
            """;

    private static final String EDIT_FILE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "old_string": { "type": "string" },
                "new_string": { "type": "string" },
                "replace_all": { "type": "boolean" }
              },
              "required": ["path", "old_string", "new_string"],
              "additionalProperties": false
            }
            """;

    private static final String GLOB_SEARCH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "pattern": { "type": "string" },
                "path": { "type": "string" }
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
            """;

    private static final String GREP_SEARCH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "pattern": { "type": "string" },
                "path": { "type": "string" },
                "output_mode": { "type": "string" }
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
            """;

    private static final String BASH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "command": { "type": "string" },
                "timeout": { "type": "integer", "minimum": 1 },
                "description": { "type": "string" },
                "run_in_background": { "type": "boolean" }
              },
              "required": ["command"],
              "additionalProperties": false
            }
            """;

    private static final String TODO_WRITE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "todos": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "string" },
                      "content": { "type": "string" },
                      "status": {
                        "type": "string",
                        "enum": ["pending", "in_progress", "completed"]
                      }
                    },
                    "required": ["content", "status"],
                    "additionalProperties": true
                  }
                }
              },
              "required": ["todos"],
              "additionalProperties": false
            }
            """;

    private static final String SLEEP_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "duration_ms": { "type": "integer", "minimum": 0 }
              },
              "required": ["duration_ms"],
              "additionalProperties": false
            }
            """;

    private static final String TOOL_SEARCH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string" },
                "max_results": { "type": "integer", "minimum": 1 }
              },
              "required": ["query"],
              "additionalProperties": false
            }
            """;

    private static final String STRUCTURED_OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": true
            }
            """;

    private static final String PLAN_MODE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """;

    private static final String SEND_USER_MESSAGE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "message": { "type": "string" }
              },
              "required": ["message"],
              "additionalProperties": false
            }
            """;

    private static final String EMPTY_OBJECT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """;

    private static final String WEB_FETCH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "url": { "type": "string", "format": "uri" },
                "prompt": { "type": "string" }
              },
              "required": ["url", "prompt"],
              "additionalProperties": false
            }
            """;

    private static final String WEB_SEARCH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "minLength": 2 },
                "allowed_domains": { "type": "array", "items": { "type": "string" } },
                "blocked_domains": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["query"],
              "additionalProperties": false
            }
            """;

    private static final String SKILL_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "skill": { "type": "string" },
                "args": { "type": "string" }
              },
              "required": ["skill"],
              "additionalProperties": false
            }
            """;

    private static final String AGENT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "description": { "type": "string" },
                "prompt": { "type": "string" },
                "subagent_type": { "type": "string" },
                "name": { "type": "string" },
                "model": { "type": "string" }
              },
              "required": ["description", "prompt"],
              "additionalProperties": false
            }
            """;

    private static final String NOTEBOOK_EDIT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "notebook_path": { "type": "string" },
                "cell_id": { "type": "string" },
                "new_source": { "type": "string" },
                "cell_type": { "type": "string", "enum": ["code", "markdown"] },
                "edit_mode": { "type": "string", "enum": ["replace", "insert", "delete"] }
              },
              "required": ["notebook_path"],
              "additionalProperties": false
            }
            """;

    private static final String REPL_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "code": { "type": "string" },
                "language": { "type": "string" },
                "timeout_ms": { "type": "integer", "minimum": 1 }
              },
              "required": ["code", "language"],
              "additionalProperties": false
            }
            """;

    private static final String POWERSHELL_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "command": { "type": "string" },
                "timeout": { "type": "integer", "minimum": 1 },
                "description": { "type": "string" },
                "run_in_background": { "type": "boolean" }
              },
              "required": ["command"],
              "additionalProperties": false
            }
            """;

    private static final String CONFIG_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "setting": { "type": "string" },
                "value": { "type": ["string", "boolean", "number"] }
              },
              "required": ["setting"],
              "additionalProperties": false
            }
            """;

    private static final String ASK_USER_QUESTION_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "question": { "type": "string" },
                "options": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["question"],
              "additionalProperties": false
            }
            """;

    private static final String TASK_CREATE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "prompt": { "type": "string" },
                "description": { "type": "string" }
              },
              "required": ["prompt"],
              "additionalProperties": false
            }
            """;

    private static final String RUN_TASK_PACKET_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "objective": { "type": "string" },
                "scope": { "type": "string" },
                "scope_path": { "type": "string" },
                "repo": { "type": "string" },
                "worktree": { "type": "string" },
                "branch_policy": { "type": "string" },
                "acceptance_tests": { "type": "array", "items": { "type": "string" } },
                "commit_policy": { "type": "string" },
                "reporting_contract": { "type": "string" },
                "escalation_policy": { "type": "string" }
              },
              "required": [
                "objective", "scope", "repo", "branch_policy",
                "acceptance_tests", "commit_policy", "reporting_contract", "escalation_policy"
              ],
              "additionalProperties": false
            }
            """;

    private static final String TASK_ID_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "task_id": { "type": "string" }
              },
              "required": ["task_id"],
              "additionalProperties": false
            }
            """;

    private static final String TASK_UPDATE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "task_id": { "type": "string" },
                "message": { "type": "string" }
              },
              "required": ["task_id", "message"],
              "additionalProperties": false
            }
            """;

    private static final String WORKER_CREATE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "cwd": { "type": "string" },
                "trusted_roots": { "type": "array", "items": { "type": "string" } },
                "auto_recover_prompt_misdelivery": { "type": "boolean" }
              },
              "required": ["cwd"],
              "additionalProperties": false
            }
            """;

    private static final String WORKER_ID_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "worker_id": { "type": "string" }
              },
              "required": ["worker_id"],
              "additionalProperties": false
            }
            """;

    private static final String WORKER_OBSERVE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "worker_id": { "type": "string" },
                "screen_text": { "type": "string" }
              },
              "required": ["worker_id", "screen_text"],
              "additionalProperties": false
            }
            """;

    private static final String WORKER_SEND_PROMPT_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "worker_id": { "type": "string" },
                "prompt": { "type": "string" },
                "task_receipt": { "type": "object", "additionalProperties": true }
              },
              "required": ["worker_id"],
              "additionalProperties": false
            }
            """;

    private static final String WORKER_OBSERVE_COMPLETION_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "worker_id": { "type": "string" },
                "finish_reason": { "type": "string" },
                "tokens_output": { "type": "integer", "minimum": 0 }
              },
              "required": ["worker_id", "finish_reason", "tokens_output"],
              "additionalProperties": false
            }
            """;

    private static final String TEAM_CREATE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "tasks": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "task_id": { "type": "string" },
                      "prompt": { "type": "string" },
                      "description": { "type": "string" }
                    },
                    "additionalProperties": true
                  }
                }
              },
              "required": ["name", "tasks"],
              "additionalProperties": false
            }
            """;

    private static final String TEAM_DELETE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "team_id": { "type": "string" }
              },
              "required": ["team_id"],
              "additionalProperties": false
            }
            """;

    private static final String CRON_CREATE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "schedule": { "type": "string" },
                "prompt": { "type": "string" },
                "description": { "type": "string" }
              },
              "required": ["schedule", "prompt"],
              "additionalProperties": false
            }
            """;

    private static final String CRON_DELETE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "cron_id": { "type": "string" }
              },
              "required": ["cron_id"],
              "additionalProperties": false
            }
            """;

    private static final String LSP_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["symbols", "references", "diagnostics", "definition", "hover", "completion", "format"]
                },
                "path": { "type": "string" },
                "line": { "type": "integer", "minimum": 0 },
                "character": { "type": "integer", "minimum": 0 },
                "query": { "type": "string" }
              },
              "required": ["action"],
              "additionalProperties": false
            }
            """;

    private static final String LIST_MCP_RESOURCES_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "server": { "type": "string" }
              },
              "additionalProperties": false
            }
            """;

    private static final String READ_MCP_RESOURCE_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "server": { "type": "string" },
                "uri": { "type": "string" }
              },
              "required": ["uri"],
              "additionalProperties": false
            }
            """;

    private static final String MCP_AUTH_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "server": { "type": "string" }
              },
              "required": ["server"],
              "additionalProperties": false
            }
            """;

    private static final String REMOTE_TRIGGER_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "url": { "type": "string" },
                "method": { "type": "string", "enum": ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"] },
                "headers": { "type": "object", "additionalProperties": true },
                "body": { "type": "string" }
              },
              "required": ["url"],
              "additionalProperties": false
            }
            """;

    private static final String MCP_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "server": { "type": "string" },
                "tool": { "type": "string" },
                "arguments": { "type": "object", "additionalProperties": true }
              },
              "required": ["server", "tool"],
              "additionalProperties": false
            }
            """;

    private static final String TESTING_PERMISSION_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string" }
              },
              "required": ["action"],
              "additionalProperties": false
            }
            """;

    private static final String MONITOR_SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "shell_id": { "type": "string" },
                "stop_on_pattern": { "type": "string" },
                "include_stdout": { "type": "boolean" }
              },
              "required": ["shell_id"],
              "additionalProperties": true
            }
            """;
}
