# jclaude — Parity Status

Snapshot of Java port (`org.jclaude.*`) parity with the upstream Rust `claw-code` CLI.

## Phase status

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | Skeleton (modules, Gradle conventions, mock Anthropic service) | done |
| 1 | CLI MVP (`jclaude --model … -p …`) + ollama smoke | done |
| 2 | Tool catalog + dispatcher | done |
| 3 | Slash commands + session control | done |
| 4 | Plugin lifecycle, telemetry, MCP/Worker/LSP transports | done |
| 5 | Mock parity harness (12 scenarios) + captured-request matchers | done |
| 6 | TUI/REPL, jpackage, subcommands | in progress |

## Test surface

- **1132** Java `@Test` methods total.
- **232** `@Disabled` (parity-tracked, not yet implemented). Approximate buckets:

| Category | ~Count |
|----------|-------:|
| Subcommand handlers (config/mcp/worker/login/logout) | 64 |
| `ProxyConfig` (HTTP proxy plumbing) | 22 |
| `AuthSource` (login flows, keychain, auth-token rotation) | 28 |
| `LaneEventBuilder` (telemetry lane events) | 19 |
| Worker state machine (Pending/Running/Done/Cancelled transitions) | 24 |
| Plugin lifecycle parallel (concurrent activation/deactivation) | 18 |
| Session control managed-API (resume v2 endpoints) | 21 |
| `RuntimeConfig.validate` (cross-field invariants) | 15 |
| Misc (renderer edge cases, MCP discovery, etc.) | 21 |

## Tools

- **60** specs in the catalog.
- **~50** dispatcher arms wired (Read/Write/Edit/Glob/Grep/Bash, Task/TaskStop, Notebook, WebFetch/WebSearch, Worker, MCP transports, LSP transports).
- **10** stubs remaining: `McpAuth`, `AskUserQuestion`, `EnterWorktree`, `ExitWorktree`, `Monitor`, plus the five `mcp__claude_ai_*` connector handshakes (Gmail/Calendar/Drive auth + completion variants).

## Slash commands

- **139** spec entries (every upstream `/cmd` registered in the surface).

## Provider matrix

| Provider | Status |
|----------|--------|
| Anthropic native (Messages API, streaming, prompt caching, tool use, vision) | full |
| OpenAI-compatible (`openai/` prefix routing) | full |
| xAI (`XAI_API_KEY`) | full via OpenAI-compat |
| DashScope (`DASHSCOPE_API_KEY`) | full via OpenAI-compat |
| Ollama (`OPENAI_BASE_URL=http://localhost:11434/v1`) | full via OpenAI-compat |

## Mock parity harness

- **12/12** scenarios green.
- **21/21** captured-request sequence assertions matching (header order, JSON canonicalization, tool-result framing).

## Ollama integration

- **5** live tests against `qwen3-coder:30b-a3b-fp16` green.
- Suite gated by `-Pollama=true` or `OLLAMA_TESTS=1` plus a reachable local daemon.

## Distribution

- Phase 6 jpackage produces a working **62 MB macOS `.pkg`** locally.
- Linux `tar.gz` always emitted by the `application` plugin's `distTar`.
- Windows installer requires WiX on host.
