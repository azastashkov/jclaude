# jclaude

A Java 21 port of the Rust [claw-code](https://github.com/azastashkov/claw-code) CLI — a multi-provider terminal coding assistant with first-class agent loops, tool dispatch, MCP, plugins, and a deterministic mock-Anthropic-service test harness.

## Quick start

```bash
./gradlew :jclaude-cli:installDist
./jclaude-cli/build/install/jclaude/bin/jclaude --version
```

Run a one-shot turn against a local ollama model:

```bash
ollama pull qwen3-coder:30b-a3b-fp16   # one-time, ~60 GB

OPENAI_BASE_URL=http://localhost:11434/v1 OPENAI_API_KEY=ollama \
  ./jclaude-cli/build/install/jclaude/bin/jclaude \
    --model openai/qwen3-coder:30b-a3b-fp16 \
    --permission-mode workspace-write \
    --allowedTools write_file \
    --output-format json \
    -p "Write hello.txt with content: hi"
```

Or launch the interactive REPL by omitting `-p`.

## Modules

| Module | What's in it |
|---|---|
| `jclaude-api` | HTTP client + SSE parser; Anthropic native (`/v1/messages`) and OpenAI-compatible (`/v1/chat/completions`) backends; provider routing for Anthropic / xAI / DashScope / ollama via model prefix. |
| `jclaude-cli` | Picocli entry point, JSON + text output, JLine 3 REPL, eight subcommands (`doctor`, `status`, `config`, `init`, `sandbox`, `agents`, `mcp`, `skills`). |
| `jclaude-commands` | 139 slash command specs with parser, dispatcher, agents/skills handlers, Levenshtein suggestion. |
| `jclaude-compat-harness` | Manifest extraction utilities consumed by the parity diff scripts. |
| `jclaude-mock-anthropic-service` | Embedded `HttpServer` mock that speaks `/v1/messages`. Twelve scripted scenarios. |
| `jclaude-plugins` | PluginManager (install / enable / disable / uninstall), manifest discovery, hooks, bundled plugins. |
| `jclaude-runtime` | `ConversationRuntime` agent loop, JSONL session persistence, permission modes, file ops, bash with 9 validation submodules, MCP (4 transports), LSP transport, OAuth PKCE, worker registry, sandbox, git context, etc. |
| `jclaude-telemetry` | `SessionTracer` + memory and JSONL sinks. |
| `jclaude-tools` | 60 tool specs, dispatcher with ~50 wired arms (read/write/edit/glob/grep/bash + task/team/cron registries + LSP + MCP bridge + Worker* + Skill + Agent + WebFetch + …). |

Java packages live under `org.jclaude.<crate>`.

## Build & test

```bash
./gradlew build                              # compile everything + tests
./gradlew test                               # unit tests only
./gradlew check                              # unit + integration
./gradlew :jclaude-cli:parityTest            # 12 deterministic parity scenarios
./gradlew :jclaude-cli:ollamaTest -Pollama=true  # 5 live tests vs qwen3-coder:30b-a3b-fp16
./gradlew checkAll -Pollama=true             # all of the above
```

Current test count: 963 across all suites (901 unit + 39 integration + 13 parity + 5 ollama + 5 misc), 0 failures, 0 skipped.

## CLI flags

| Flag | Default | Notes |
|---|---|---|
| `--model <name>` | `claude-sonnet-4-6` | Aliases (`sonnet`, `haiku`, `opus`, `grok`, `kimi`) plus prefixed names (`openai/...`, `qwen/...`, `claude-...`). |
| `--output-format text\|json` | `text` | JSON shape: `{ kind, model, message, iterations, tool_uses, tool_results, usage, estimated_cost }`. |
| `--permission-mode read-only\|workspace-write\|danger-full-access` | `read-only` | Gates tool execution. |
| `--allowedTools <csv>` | (all) | Restrict the tool surface offered to the model. |
| `-p, --print <prompt>` | — | One-shot mode (skip the REPL). |
| `--resume <session-id-or-path>` | — | Resume a JSONL session. |
| `--compact` | off | Terse text output. |
| `--dangerously-skip-permissions` | off | Skip user approval prompts. |
| `--max-tokens <n>` | model-specific | Override the per-model default. |

## Environment variables

`ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `XAI_API_KEY`, `XAI_BASE_URL`, `DASHSCOPE_API_KEY`, `DASHSCOPE_BASE_URL`, `JCLAUDE_CONFIG_HOME`, `NO_COLOR`, `OLLAMA_TESTS`, `JCLAUDE_BIN`, `JCLAUDE_MCP_BRIDGE_ENABLED`, `JCLAUDE_WORKER_REGISTRY_ENABLED`.

## Subcommands

```
jclaude doctor   --output-format json   # health check
jclaude status   --output-format json   # session + model status
jclaude config get|set|list             # settings
jclaude init                            # initialize .jclaude/ in cwd
jclaude sandbox                         # sandbox availability
jclaude agents                          # list discovered agents
jclaude mcp                             # list MCP servers
jclaude skills                          # list skills
```

## Distribution

```bash
./gradlew :jclaude-cli:installDist          # bin/jclaude + bin/jclaude.bat
./gradlew :jclaude-cli:distZip              # standalone .zip
./gradlew :jclaude-cli:distTar              # standalone .tar
./gradlew :jclaude-cli:shadowJar            # fat jar (java -jar)
./gradlew :jclaude-cli:jpackage             # native installer (macOS .pkg verified; Linux app-image; Windows .exe host-only)
```

## What's verified

- Mock-parity harness: 12 / 12 scripted scenarios pass against a Java port of the Rust mock service. The captured-request sequence verifier asserts exactly 21 `/v1/messages` requests in fixed scenario order — byte-equivalent to the Rust harness.
- Ollama integration: 5 / 5 live tests pass against `qwen3-coder:30b-a3b-fp16` covering streaming, write_file, read_file, bash, usage tokens.
- Multi-provider routing: ollama via OpenAI-compat, Anthropic native, xAI/DashScope reachable via model prefix.

## Plan & history

The full multi-phase plan that drove this port lives at `/Users/azastashkov/config/claude/plans/implement-a-full-port-snappy-whale.md`. See also `PARITY-J.md` and `USAGE-J.md` for status and reference.

## License

MIT.
