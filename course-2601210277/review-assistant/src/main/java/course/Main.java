package course;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: Main worker|review config.local.properties [source-file]");
        Config config = new Config(Path.of(args[1]));
        if ("worker".equals(args[0])) {
            Bus bus = new Bus(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { bus.close(); } catch (Exception ignored) { } }));
            Model model = new Model(config);
            for (String role : List.of("style", "logic", "security", "summary")) bus.startWorker(role, model);
            System.out.println("WORKERS_READY student=" + Config.ID);
            new CountDownLatch(1).await();
        } else if ("review".equals(args[0])) {
            if (args.length != 3) throw new IllegalArgumentException("Provide a source file; input is sent to the configured model");
            Path source = Path.of(args[2]);
            if (Files.size(source) > 100000) throw new IllegalArgumentException("Source must be <= 100 KB");
            String session = Config.ID + "-review-" + UUID.randomUUID();
            String input = Files.readString(source);
            try (Bus bus = new Bus(config)) {
                bus.subscribeSession(session);
                Map<String, CompletableFuture<String>> jobs = new LinkedHashMap<>();
                for (String role : List.of("style", "logic", "security")) jobs.put(role, bus.send(role, session, input));
                Map<String, JsonNode> results = new LinkedHashMap<>();
                CompletableFuture.allOf(jobs.values().toArray(CompletableFuture[]::new)).get(125, TimeUnit.SECONDS);
                for (var entry : jobs.entrySet()) results.put(entry.getKey(), Protocol.result(entry.getValue().join()));
                JsonNode summary = Protocol.result(bus.send("summary", session, Model.JSON.writeValueAsString(results)).get(125, TimeUnit.SECONDS));
                Path output = Path.of("results", session + ".json");
                Files.createDirectories(output.getParent());
                Model.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("student", Config.ID,
                    "name", "常何小丫", "session", session, "reviews", results, "summary", summary, "mode", "cloud-model-and-rocketmq"));
                System.out.println("REVIEW_COMPLETE file=" + output.toAbsolutePath());
            }
        } else throw new IllegalArgumentException("Unknown command");
    }
}
