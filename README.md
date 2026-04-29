# jclaude

A Java 21 port of [claw-code](https://github.com/azastashkov/claw-code)'s Rust CLI. Full surface parity: all 9 modules, all commands, all tools, MCP, plugins, telemetry, and a deterministic mock-Anthropic-service test harness.

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew :jclaude-cli:installDist
./jclaude-cli/build/install/jclaude/bin/jclaude --version
```

Or via the fat jar:

```bash
./gradlew :jclaude-cli:shadowJar
java -jar jclaude-cli/build/libs/jclaude-cli-*-all.jar --version
```

## Tests

```bash
./gradlew test                 # unit (~1072)
./gradlew check                # unit + integration (~1160)
./gradlew :jclaude-cli:parityTest        # 12 mock parity scenarios
./gradlew :jclaude-cli:ollamaTest -Pollama=true   # 13 ollama integration tests (requires local ollama + qwen3-coder:30b-a3b-fp16)
./gradlew checkAll -Pollama=true         # everything
```

## Modules

See `settings.gradle`.

## License

MIT.
