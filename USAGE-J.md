# jclaude — Usage

Java 21 port of `claw-code`. Single-binary CLI plus runtime/plugin/tool modules.

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew :jclaude-cli:installDist
./jclaude-cli/build/install/jclaude/bin/jclaude --version
```

Fat-jar variant:

```bash
./gradlew :jclaude-cli:shadowJar
java -jar jclaude-cli/build/libs/jclaude-cli-*-all.jar --version
```

## Native installer (Phase 6)

```bash
./gradlew :jclaude-cli:jpackage
```

Host-OS-only output:

| OS      | Artifact                                  | Notes |
|---------|-------------------------------------------|-------|
| macOS   | `build/jpackage/jclaude-1.0.0.pkg`        | Universal `.pkg`. `.dmg` skipped (needs signing). |
| Linux   | `build/distributions/jclaude-*.tar.gz` (always) plus `build/jpackage/jclaude_*.deb` if `fakeroot`/`dpkg` are installed | `tar.gz` from the `application` plugin's `distTar` task. |
| Windows | `build/jpackage/jclaude-0.1.0.exe`        | Requires WiX 3.x on `PATH`. Not cross-compilable; run on a Windows host. |

## CLI flags

| Flag | Description |
|------|-------------|
| `--model <id>` | Model id or alias. Default `claude-sonnet-4-6`. Prefix `openai/` routes to the OpenAI-compatible provider (xAI/DashScope/Ollama/OpenRouter). |
| `--output-format <text\|json>` | Output format. Default `text`. |
| `--permission-mode <read-only\|workspace-write\|danger-full-access>` | Tool-permission posture. Default `read-only`. |
| `--allowedTools <csv>` | Comma-separated tool whitelist. Default: all MVP tools. |
| `-p, --print <prompt>` | One-shot prompt. Alternative to positional args. |
| `--resume <id\|path>` | Resume a session id or path to a JSONL session log. |
| `--compact` | Print only the assistant text, no tool trace. |
| `--dangerously-skip-permissions` | Auto-approve every permission prompt. |
| `--max-tokens <n>` | Max output tokens. `0` = model default. |
| `--version` | Print version and exit. |
| `-h, --help` | Print usage and exit. |

## Environment variables

| Variable | Role |
|----------|------|
| `ANTHROPIC_API_KEY` | Anthropic native provider credential. |
| `ANTHROPIC_AUTH_TOKEN` | Alternative Anthropic credential (Bearer token). |
| `ANTHROPIC_BASE_URL` | Override Anthropic endpoint (default `https://api.anthropic.com`). |
| `OPENAI_API_KEY` | OpenAI-compatible provider credential. |
| `OPENAI_BASE_URL` | Override OpenAI-compatible endpoint (default `https://api.openai.com/v1`). Use this for Ollama/OpenRouter/local servers. |
| `XAI_API_KEY` | xAI provider credential (prefix-routed via `--model openai/grok-*`). |
| `DASHSCOPE_API_KEY` | Alibaba DashScope credential. |
| `JCLAUDE_CONFIG_HOME` | Override config directory (default `~/.config/jclaude`). |
| `JCLAUDE_BIN` | Path to a built `jclaude` binary; consumed by the integration/parity/ollama suites. |
| `JCLAUDE_MCP_BRIDGE_ENABLED` | Toggle the MCP bridge dispatcher (default off). |
| `JCLAUDE_WORKER_REGISTRY_ENABLED` | Toggle the worker registry dispatcher (default off). |
| `OLLAMA_TESTS` | Set to opt the ollama suite into runs that don't pass `-Pollama=true`. |
| `NO_COLOR` | Disable ANSI color in renderer output. |

## Tests

```bash
./gradlew test                                       # unit (~1072)
./gradlew check                                      # unit + integration (~1160)
./gradlew :jclaude-cli:parityTest                    # 12 mock-Anthropic parity scenarios
./gradlew :jclaude-cli:ollamaTest -Pollama=true      # live ollama against qwen3-coder:30b-a3b-fp16
./gradlew checkAll -Pollama=true                     # everything
```

## Smoke (canonical Phase 1 done-criterion)

Requires local `ollama` serving `qwen3-coder:30b-a3b-fp16`:

```bash
ollama pull qwen3-coder:30b-a3b-fp16
ollama serve &

OPENAI_BASE_URL=http://localhost:11434/v1 \
OPENAI_API_KEY=ollama \
./jclaude-cli/build/install/jclaude/bin/jclaude \
  --model openai/qwen3-coder:30b-a3b-fp16 \
  --compact \
  -p "say hello"
```

## License

MIT.
