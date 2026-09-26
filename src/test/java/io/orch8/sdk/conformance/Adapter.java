package io.orch8.sdk.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.orch8.sdk.Orch8ApiException;
import io.orch8.sdk.Orch8Client;
import io.orch8.sdk.Orch8Config;
import io.orch8.sdk.Orch8TransportException;
import io.orch8.sdk.model.CreateInstanceRequest;
import io.orch8.sdk.model.EnqueueJobRequest;
import io.orch8.sdk.model.Query;
import io.orch8.sdk.model.SignalType;
import io.orch8.sdk.push.PushReceiver;
import io.orch8.sdk.push.PushSignature;
import io.orch8.sdk.worker.NonRetryableTaskException;
import io.orch8.sdk.worker.Orch8Worker;
import io.orch8.sdk.worker.RetryableTaskException;
import io.orch8.sdk.worker.TaskContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Conformance adapter for sdk-contract/conformance (modes: worker | push | verify | client).
 * Built only on the SDK's public API.
 */
public final class Adapter {
  private static final ObjectMapper M = Orch8Client.mapper();
  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

  private Adapter() {}

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "";
    switch (mode) {
      case "worker": runWorker(); break;
      case "push": runPush(); break;
      case "verify": runVerify(); break;
      case "client": runClient(); break;
      default:
        System.err.println("usage: conformance <worker|push|verify|client>");
        System.exit(2);
    }
  }

  // ---------------------------------------------------------------------------

  static String env(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isEmpty() ? def : v;
  }

  static Orch8Client client() {
    return new Orch8Client(Orch8Config.fromEnv()
        .maxAttempts(Integer.parseInt(env("ORCH8_MAX_ATTEMPTS", "3")))
        .retryBaseDelay(Duration.ofMillis(Long.parseLong(env("ORCH8_RETRY_BASE_DELAY_MS", "20"))))
        .build());
  }

  static Orch8Worker.Builder workerBuilder(Orch8Client client) {
    Orch8Worker.Builder b = Orch8Worker.builder(client)
        .concurrency(Integer.parseInt(env("ORCH8_CONCURRENCY", "4")))
        .pollInterval(Duration.ofMillis(Long.parseLong(env("ORCH8_POLL_INTERVAL_MS", "100"))))
        .shutdownTimeout(Duration.ofMillis(Long.parseLong(env("ORCH8_SHUTDOWN_TIMEOUT_MS", "10000"))))
        .queue(env("ORCH8_QUEUE", null))
        .version(env("ORCH8_WORKER_VERSION", null));
    String id = env("ORCH8_WORKER_ID", null);
    if (id != null) {
      b.workerId(id);
    }
    return registerHandlers(b);
  }

  static Orch8Worker.Builder registerHandlers(Orch8Worker.Builder b) {
    return b
        .handler("echo", ctx -> Map.of("echo", ctx.params() == null ? NullNode.getInstance() : ctx.params()))
        .handler("fail_retryable", ctx -> {
          throw new RetryableTaskException(ctx.params() != null && ctx.params().hasNonNull("message")
              ? ctx.params().get("message").asText() : "boom");
        })
        .handler("fail_permanent", ctx -> {
          throw new NonRetryableTaskException("fatal");
        })
        .handler("crash", ctx -> {
          throw new IllegalStateException("crash");
        })
        .handler("checkpoint", Adapter::checkpointHandler)
        .handler("slow", ctx -> {
          long ms = ctx.params() != null && ctx.params().has("sleep_ms") ? ctx.params().get("sleep_ms").asLong() : 1000;
          ctx.cancellation().sleep(ms);
          return Map.of("slept", ms);
        });
  }

  static Object checkpointHandler(TaskContext ctx) {
    JsonNode resume = ctx.resumeCheckpoint();
    long start = resume != null && resume.has("step") ? resume.get("step").asLong() : 0;
    long steps = ctx.params() != null && ctx.params().has("steps") ? ctx.params().get("steps").asLong() : 3;
    for (long i = start + 1; i <= steps; i++) {
      ctx.checkpoint(Map.of("step", i));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resumed_from", start);
    out.put("final_step", steps);
    return out;
  }

  /** Blocks until SIGTERM, then runs {@code onTerm} and exits 0. */
  @SuppressWarnings("restriction")
  static void awaitSigterm(Runnable onTerm) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    sun.misc.Signal.handle(new sun.misc.Signal("TERM"), sig -> latch.countDown());
    latch.await();
    onTerm.run();
    System.exit(0);
  }

  // ---------------------------------------------------------------------------

  static void runWorker() throws Exception {
    Orch8Worker worker = workerBuilder(client()).build();
    worker.start();
    awaitSigterm(worker::stop);
  }

  static void runPush() throws Exception {
    Orch8Worker worker = workerBuilder(client()).polling(false).build();
    worker.start();
    PushReceiver receiver = new PushReceiver(worker, env("ORCH8_PUSH_SECRET", null),
        Long.parseLong(env("ORCH8_PUSH_TOLERANCE_SECS", "300")));
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0",
        Integer.parseInt(env("ORCH8_PUSH_PORT", "0"))), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", exchange -> {
      try (exchange) {
        byte[] body = exchange.getRequestBody().readAllBytes();
        int status = "POST".equals(exchange.getRequestMethod())
            ? receiver.handle(exchange.getRequestHeaders().getFirst(PushSignature.TIMESTAMP_HEADER),
                exchange.getRequestHeaders().getFirst(PushSignature.SIGNATURE_HEADER), body)
            : 405;
        exchange.sendResponseHeaders(status, -1);
      }
    });
    server.start();
    OUT.println("READY");
    awaitSigterm(() -> {
      server.stop(0);
      worker.stop();
    });
  }

  static void runVerify() throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode v = M.readTree(line);
      boolean valid = PushSignature.verify(
          v.path("secret").asText(),
          text(v.get("timestamp")),
          text(v.get("signature")),
          v.path("body").asText().getBytes(StandardCharsets.UTF_8),
          v.hasNonNull("now") ? v.get("now").asLong() : null,
          v.hasNonNull("tolerance_secs") ? v.get("tolerance_secs").asLong() : PushSignature.DEFAULT_TOLERANCE_SECS);
      OUT.println(M.writeValueAsString(Map.of("valid", valid)));
    }
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  // ---------------------------------------------------------------------------

  static void runClient() throws Exception {
    Orch8Client client = client();
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode req = M.readTree(line);
      ObjectNode res = M.createObjectNode();
      res.set("id", req.get("id"));
      try {
        Object result = clientOp(client, req.path("op").asText(), req.path("args"));
        res.put("ok", true);
        res.set("result", result == null ? NullNode.getInstance() : M.valueToTree(result));
      } catch (Orch8ApiException e) {
        res.put("ok", false);
        ObjectNode err = res.putObject("error");
        err.put("kind", e.kind().wireName());
        err.put("status", e.status());
        err.put("code", e.code());
        err.put("message", e.getMessage());
      } catch (Orch8TransportException e) {
        res.put("ok", false);
        ObjectNode err = res.putObject("error");
        err.put("kind", "transport");
        err.putNull("status");
        err.putNull("code");
        err.put("message", String.valueOf(e.getMessage()));
      }
      OUT.println(M.writeValueAsString(res));
    }
  }

  static Object clientOp(Orch8Client c, String op, JsonNode a) throws Exception {
    switch (op) {
      case "sequences.create": return c.sequences().create(a.get("body"));
      case "sequences.get": return c.sequences().get(a.path("id").asText());
      case "sequences.list": return c.sequences().list(query(a));
      case "instances.create": return c.instances().create(M.treeToValue(a.get("body"), CreateInstanceRequest.class));
      case "instances.get": return c.instances().get(a.path("id").asText());
      case "instances.list": return c.instances().list(query(a));
      case "instances.signal":
        return c.instances().signal(a.path("id").asText(), M.treeToValue(a.get("signal_type"), SignalType.class),
            a.has("payload") ? a.get("payload") : null);
      case "instances.cancel": return c.instances().cancel(a.path("id").asText());
      case "jobs.enqueue": return c.jobs().enqueue(M.treeToValue(a.get("body"), EnqueueJobRequest.class));
      case "jobs.get": return c.jobs().get(a.path("id").asText());
      case "jobs.list": return c.jobs().list(query(a));
      case "jobs.cancel": return c.jobs().cancel(a.path("id").asText()).orElse(null);
      default: throw new IllegalArgumentException("unknown op " + op);
    }
  }

  @SuppressWarnings("unchecked")
  static Query query(JsonNode a) {
    JsonNode q = a.get("query");
    if (q == null || q.isNull()) {
      return null;
    }
    Map<String, Object> m = M.convertValue(q, Map.class);
    return Query.of(m);
  }
}
