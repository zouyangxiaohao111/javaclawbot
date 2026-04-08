package cli;

import bus.InboundMessage;
import bus.OutboundMessage;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentConsoleSession {

    private final AgentRuntime runtime;
    private final String sessionId;
    private final boolean markdown;

    public AgentConsoleSession(AgentRuntime runtime, String sessionId, boolean markdown) {
        this.runtime = runtime;
        this.sessionId = sessionId;
        this.markdown = markdown;
    }

    public void run() {
        String[] pair = splitSession(sessionId);
        String cliChannel = pair[0];
        String cliChatId = pair[1];

        Path histFile = Paths.get(System.getProperty("user.home"), ".javaclawbot", "history", "cli_history");
        try {
            Files.createDirectories(histFile.getParent());
        } catch (IOException ignored) {
        }

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to init terminal", e);
        }

        DefaultHistory history;
        try {
            history = new DefaultHistory();
            history.read(histFile, false);
        } catch (Exception e) {
            history = new DefaultHistory();
        }

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .build();

        if (reader instanceof LineReaderImpl impl) {
            impl.setVariable(LineReader.HISTORY_SIZE, 10000);
        }

        System.out.println("🐱 Interactive mode (type exit or Ctrl+C to quit)\n");

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<CountDownLatch> turnLatchRef = new AtomicReference<>(new CountDownLatch(0));
        AtomicReference<String> turnResponseRef = new AtomicReference<>(null);

        ExecutorService outboundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "javaclawbot-cli-outbound");
            t.setDaemon(false);
            return t;
        });

        CompletableFuture<Void> outboundTask = CompletableFuture.runAsync(() -> {
            while (running.get() && runtime.isRunning()) {
                try {
                    OutboundMessage out = runtime.consumeOutbound(1, TimeUnit.SECONDS);
                    if (out == null) continue;

                    Map<String, Object> meta = out.getMetadata() != null ? out.getMetadata() : Map.of();
                    boolean isProgress = Boolean.TRUE.equals(meta.get("_progress"));

                    if (isProgress) {
                        System.out.println("  ↳ " + (out.getContent() == null ? "" : out.getContent()));
                        continue;
                    }

                    CountDownLatch latch = turnLatchRef.get();
                    if (latch != null && latch.getCount() > 0) {
                        if (out.getContent() != null && !out.getContent().isBlank()) {
                            turnResponseRef.compareAndSet(null, out.getContent());
                        }
                        latch.countDown();
                    } else {
                        if (out.getContent() != null && !out.getContent().isBlank()) {
                            printAgentResponse(out.getContent(), markdown);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, outboundExecutor);

        try {
            while (true) {
                String userInput;
                try {
                    userInput = reader.readLine("You: ");
                } catch (UserInterruptException | EndOfFileException e) {
                    System.out.println("\nGoodbye!");
                    break;
                }

                if (userInput == null || userInput.trim().isEmpty()) continue;
                if (isExitCommand(userInput.trim())) {
                    System.out.println("\nGoodbye!");
                    break;
                }

                try {
                    history.add(userInput);
                    history.save();
                } catch (Exception ignored) {
                }

                turnResponseRef.set(null);
                CountDownLatch latch = new CountDownLatch(1);
                turnLatchRef.set(latch);

                runtime.publishInbound(new InboundMessage(
                        cliChannel, "user", cliChatId, userInput, null, null
                )).toCompletableFuture().join();

                System.out.println("[dim]javaclawbot is thinking...[/dim]");

                boolean ok = latch.await(10, TimeUnit.MINUTES);
                String resp = turnResponseRef.get();
                turnLatchRef.set(new CountDownLatch(0));

                if (!ok) {
                    System.out.println("Timed out waiting for response.");
                } else if (resp != null && !resp.isBlank()) {
                    printAgentResponse(resp, markdown);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            outboundTask.cancel(true);
            outboundExecutor.shutdownNow();
            try {
                history.save();
            } catch (Exception ignored) {
            }
        }
    }

    private static String[] splitSession(String sessionId) {
        if (sessionId != null && sessionId.contains(":")) {
            String[] parts = sessionId.split(":", 2);
            return new String[]{parts[0], parts[1]};
        }
        return new String[]{"cli", sessionId != null ? sessionId : "direct"};
    }

    private static boolean isExitCommand(String s) {
        return "exit".equalsIgnoreCase(s) || "quit".equalsIgnoreCase(s);
    }

    private static void printAgentResponse(String content, boolean markdown) {
        String prefix = """
                🐱 javaclawbot output: \n
                """;
        System.out.println(content == null ? "" : prefix + content);
    }
}