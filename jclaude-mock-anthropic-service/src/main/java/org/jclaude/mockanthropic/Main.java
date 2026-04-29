package org.jclaude.mockanthropic;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * CLI entrypoint for the standalone mock service. Prints {@code MOCK_ANTHROPIC_BASE_URL=<url>} on
 * startup and blocks until the JVM is interrupted (e.g. SIGINT).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        String bind = "127.0.0.1:0";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--bind".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --bind");
                }
                bind = args[++i];
            } else if (arg.startsWith("--bind=")) {
                bind = arg.substring("--bind=".length());
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Usage: jclaude-mock-anthropic-service [--bind HOST:PORT]");
                return;
            } else {
                throw new IllegalArgumentException("unsupported argument: " + arg);
            }
        }

        MockAnthropicService server = MockAnthropicService.spawn_on(bind);
        System.out.println("MOCK_ANTHROPIC_BASE_URL=" + server.base_url());
        System.out.flush();

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            try {
                                server.close();
                            } finally {
                                latch.countDown();
                            }
                        },
                        "mock-anthropic-shutdown"));

        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
        }
    }
}
