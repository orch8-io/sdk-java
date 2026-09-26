package io.orch8.sdk.worker;

import static io.orch8.sdk.testing.FakeServer.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orch8.sdk.Orch8Client;
import io.orch8.sdk.push.PushReceiver;
import io.orch8.sdk.push.PushSignature;
import io.orch8.sdk.testing.FakeServer;
import io.orch8.sdk.testing.FakeServer.Req;
import io.orch8.sdk.testing.FakeServer.Res;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerTest {
  FakeServer server;
  Orch8Client client;
  final Deque<ObjectNode> pending = new ArrayDeque<>();
  final Map<String, Integer> checkpointSeq = new ConcurrentHashMap<>();
  final AtomicInteger inFlight = new AtomicInteger();
  final AtomicInteger maxInFlight = new AtomicInteger();
  volatile Function<Req, Res> mutationHook = r -> null;
  volatile int heartbeatHintSecs = 15;
  Orch8Worker worker;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeServer().handle(this::engine);
    client = Orch8Client.builder().baseUrl(server.baseUrl()).apiKey("k").tenantId("t").build();
  }

  @AfterEach
  void tearDown() {
    if (worker != null) {
      worker.stop(Duration.ofSeconds(2));
    }
    server.close();
  }

  ObjectNode task(String handler, Object params) {
    ObjectNode t = FakeServer.M.createObjectNode();
    String id = "task-" + (pending.size() + 1) + "-" + handler;
    t.put("id", id);
    t.put("instance_id", "inst-1");
    t.put("block_id", "b1");
    t.put("handler_name", handler);
    t.set("params", FakeServer.M.valueToTree(params));
    t.set("context", FakeServer.M.createObjectNode());
    t.put("attempt", 0);
    t.put("state", "claimed");
    t.put("claim_epoch", 3);
    t.put("checkpoint_seq", 0);
    t.put("created_at", OffsetDateTime.now().toString());
    t.put("unknown_future_field", true);
    synchronized (pending) {
      pending.add(t);
    }
    return t;
  }

  Res engine(Req r) {
    String p = r.path();
    if (p.startsWith("/workers/tasks/poll")) {
      int limit = r.body.get("limit").asInt();
      String handler = r.body.get("handler_name").asText();
      ArrayNode tasks = FakeServer.M.createArrayNode();
      synchronized (pending) {
        for (var it = pending.iterator(); it.hasNext() && tasks.size() < limit; ) {
          ObjectNode t = it.next();
          if (t.get("handler_name").asText().equals(handler)) {
            it.remove();
            tasks.add(t);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          }
        }
      }
      return Res.json(200, Map.of("tasks", tasks, "lease_secs", heartbeatHintSecs * 4,
          "heartbeat_interval_secs", heartbeatHintSecs, "poll_after_ms", tasks.isEmpty() ? 50 : 0));
    }
    Res hooked = mutationHook.apply(r);
    if (hooked != null) {
      return hooked;
    }
    if (p.endsWith("/heartbeat")) {
      String id = p.split("/")[3];
      if (r.body.has("checkpoint")) {
        int expected = checkpointSeq.getOrDefault(id, 0);
        if (r.body.get("checkpoint_seq").asInt() != expected) {
          return Res.error(409, "conflict", "stale seq");
        }
        checkpointSeq.put(id, expected + 1);
      }
      return Res.json(200, Map.of("checkpoint_seq", checkpointSeq.getOrDefault(id, 0)));
    }
    if (p.endsWith("/complete") || p.endsWith("/fail")) {
      inFlight.decrementAndGet();
      return new Res(200, "");
    }
    return Res.error(404, "not_found", "no route");
  }

  Orch8Worker.Builder builder() {
    return Orch8Worker.builder(client).workerId("w-1").concurrency(2).pollInterval(Duration.ofMillis(20));
  }

  List<Req> acks(String taskId) {
    return server.requests().stream()
        .filter(r -> r.path().equals("/workers/tasks/" + taskId + "/complete") || r.path().equals("/workers/tasks/" + taskId + "/fail"))
        .collect(Collectors.toList());
  }

  List<Req> heartbeats(String taskId) {
    return server.find("POST", "/workers/tasks/" + taskId + "/heartbeat");
  }

  @Test
  void pollsExecutesAndCompletesWithClaimEpoch() {
    ObjectNode t = task("echo", Map.of("n", 7));
    AtomicReference<TaskContext> seen = new AtomicReference<>();
    worker = builder().handler("echo", ctx -> {
      seen.set(ctx);
      return Map.of("echo", ctx.params());
    }).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "complete");
    Req ack = acks(t.get("id").asText()).get(0);
    assertTrue(ack.path().endsWith("/complete"));
    assertEquals("w-1", ack.body.get("worker_id").asText());
    assertEquals(3, ack.body.get("claim_epoch").asInt());
    assertEquals(7, ack.body.at("/output/echo/n").asInt());
    assertEquals("inst-1", seen.get().instanceId());
    assertTrue(seen.get().task().extra("unknown_future_field").asBoolean());
    for (Req poll : server.find("POST", "/workers/tasks/poll")) {
      assertEquals("echo", poll.body.get("handler_name").asText());
      assertTrue(poll.body.get("limit").asInt() >= 1 && poll.body.get("limit").asInt() <= 2);
      assertFalse(poll.body.has("queue_name"));
      assertEquals("k", poll.header("x-api-key"));
    }
  }

  @Test
  void nullOutputIsSentAsEmptyObject() {
    ObjectNode t = task("noop", null);
    worker = builder().handler("noop", ctx -> null).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "complete");
    assertEquals("{}", acks(t.get("id").asText()).get(0).body.get("output").toString());
  }

  @Test
  void failureClassification() {
    ObjectNode retry = task("r", Map.of());
    ObjectNode perm = task("p", Map.of());
    ObjectNode crash = task("c", Map.of());
    worker = builder()
        .handler("r", ctx -> { throw new RetryableTaskException("boom"); })
        .handler("p", ctx -> { throw TaskException.permanent("fatal"); })
        .handler("c", ctx -> { throw new IllegalStateException(); })
        .build().start();
    waitFor(() -> List.of(retry, perm, crash).stream().allMatch(t -> acks(t.get("id").asText()).size() == 1),
        Duration.ofSeconds(5), "fails");
    JsonNode r = acks(retry.get("id").asText()).get(0).body;
    assertEquals("boom", r.get("message").asText());
    assertTrue(r.get("retryable").asBoolean());
    assertFalse(acks(perm.get("id").asText()).get(0).body.get("retryable").asBoolean());
    JsonNode c = acks(crash.get("id").asText()).get(0).body;
    assertTrue(c.get("retryable").asBoolean());
    assertEquals("java.lang.IllegalStateException", c.get("message").asText());
  }

  @Test
  void checkpointTracksCasSequence() {
    ObjectNode t = task("cp", Map.of());
    t.put("checkpoint_seq", 5);
    t.set("resume_checkpoint", FakeServer.M.valueToTree(Map.of("step", 1)));
    checkpointSeq.put(t.get("id").asText(), 5);
    worker = builder().handler("cp", ctx -> {
      int start = ctx.resumeCheckpoint().get("step").asInt();
      assertEquals(5, ctx.checkpointSeq());
      ctx.checkpoint(Map.of("step", start + 1));
      long seq = ctx.checkpoint(Map.of("step", start + 2));
      return Map.of("seq", seq);
    }).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "complete");
    List<Req> cps = heartbeats(t.get("id").asText());
    assertEquals(5, cps.get(0).body.get("checkpoint_seq").asInt());
    assertEquals(6, cps.get(1).body.get("checkpoint_seq").asInt());
    assertEquals(7, acks(t.get("id").asText()).get(0).body.at("/output/seq").asInt());
  }

  @Test
  void leaseLossCancelsHandlerAndSuppressesAck() {
    heartbeatHintSecs = 1;
    ObjectNode t = task("slow", Map.of());
    String id = t.get("id").asText();
    AtomicReference<CancellationReason> reason = new AtomicReference<>();
    mutationHook = r -> r.path().equals("/workers/tasks/" + id + "/heartbeat") ? Res.error(409, "conflict", "lease changed") : null;
    worker = builder().handler("slow", ctx -> {
      ctx.cancellation().sleep(10_000);
      reason.set(ctx.cancellation().reason());
      return Map.of();
    }).build().start();
    waitFor(() -> reason.get() != null, Duration.ofSeconds(5), "cancellation");
    assertEquals(CancellationReason.LEASE_LOST, reason.get());
    int hb = heartbeats(id).size();
    sleep(1500);
    assertEquals(hb, heartbeats(id).size(), "no heartbeats after lease loss");
    assertTrue(acks(id).isEmpty(), "no ack after lease loss");
    assertEquals(0, worker.inFlightCount());
  }

  @Test
  void completeRetriedWithIdenticalBodyOn503ButNotOn409() {
    ObjectNode flaky = task("e", Map.of("which", "flaky"));
    ObjectNode stolen = task("e", Map.of("which", "stolen"));
    AtomicInteger flakyCalls = new AtomicInteger();
    mutationHook = r -> {
      if (r.path().endsWith(flaky.get("id").asText() + "/complete") && flakyCalls.incrementAndGet() == 1) {
        return Res.error(503, "unavailable", "busy");
      }
      if (r.path().endsWith(stolen.get("id").asText() + "/complete")) {
        return Res.error(409, "conflict", "stolen");
      }
      return null;
    };
    worker = builder().handler("e", ctx -> Map.of("p", ctx.params())).build().start();
    waitFor(() -> acks(flaky.get("id").asText()).size() == 2 && acks(stolen.get("id").asText()).size() == 1,
        Duration.ofSeconds(5), "acks");
    sleep(500);
    List<Req> f = acks(flaky.get("id").asText());
    assertEquals(f.get(0).rawBody, f.get(1).rawBody);
    assertEquals(1, acks(stolen.get("id").asText()).size());
  }

  @Test
  void localTimeoutFailsRetryableAndCancelsHandler() {
    ObjectNode t = task("slow", Map.of());
    t.put("timeout_ms", 300);
    AtomicReference<CancellationReason> reason = new AtomicReference<>();
    worker = builder().handler("slow", ctx -> {
      ctx.cancellation().sleep(10_000);
      reason.set(ctx.cancellation().reason());
      return Map.of();
    }).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "fail");
    JsonNode body = acks(t.get("id").asText()).get(0).body;
    assertTrue(body.get("retryable").asBoolean());
    assertTrue(body.get("message").asText().contains("timed out"));
    waitFor(() -> reason.get() != null, Duration.ofSeconds(2), "cancel");
    assertEquals(CancellationReason.TIMEOUT, reason.get());
  }

  @Test
  void concurrencyLimitIsNeverExceeded() {
    for (int i = 0; i < 6; i++) {
      task("slow", Map.of());
    }
    worker = builder().handler("slow", ctx -> {
      Thread.sleep(150);
      return Map.of();
    }).handler("other", ctx -> Map.of()).build().start();
    waitFor(() -> server.requests().stream().filter(r -> r.path().endsWith("/complete")).count() == 6,
        Duration.ofSeconds(10), "all complete");
    assertEquals(2, maxInFlight.get());
  }

  @Test
  void queueAndVersionAreSent() {
    ObjectNode t = task("echo", Map.of());
    worker = builder().queue("gpu").version("2.3.4").handler("echo", ctx -> Map.of()).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "complete");
    List<Req> polls = server.find("POST", "/workers/tasks/poll/queue");
    assertFalse(polls.isEmpty());
    assertEquals("gpu", polls.get(0).body.get("queue_name").asText());
    assertEquals("2.3.4", polls.get(0).body.get("version").asText());
    assertTrue(server.find("POST", "/workers/tasks/poll").isEmpty());
  }

  @Test
  void heartbeatsHonourServerHint() {
    heartbeatHintSecs = 1;
    ObjectNode t = task("slow", Map.of());
    worker = builder().handler("slow", ctx -> {
      ctx.cancellation().sleep(2300);
      return Map.of();
    }).build().start();
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(6), "complete");
    assertEquals(Duration.ofSeconds(1), worker.heartbeatInterval());
    List<Req> hbs = heartbeats(t.get("id").asText());
    assertTrue(hbs.size() >= 2, "heartbeats: " + hbs.size());
    assertFalse(hbs.get(0).body.has("checkpoint"));
  }

  @Test
  void gracefulStopDrainsInFlightWork() {
    ObjectNode t = task("slow", Map.of());
    worker = builder().handler("slow", ctx -> {
      Thread.sleep(500);
      return Map.of("done", true);
    }).build().start();
    waitFor(() -> worker.inFlightCount() == 1, Duration.ofSeconds(5), "claim");
    assertTrue(worker.stop(Duration.ofSeconds(5)));
    int polls = server.find("POST", "/workers/tasks/poll").size();
    assertEquals(1, acks(t.get("id").asText()).size());
    sleep(200);
    assertEquals(polls, server.find("POST", "/workers/tasks/poll").size());
    assertFalse(worker.isRunning());
  }

  @Test
  void drainTimeoutAbandonsWithoutAck() {
    ObjectNode t = task("stuck", Map.of());
    AtomicReference<CancellationReason> reason = new AtomicReference<>();
    worker = builder().handler("stuck", ctx -> {
      ctx.cancellation().sleep(10_000);
      reason.set(ctx.cancellation().reason());
      return Map.of();
    }).build().start();
    waitFor(() -> worker.inFlightCount() == 1, Duration.ofSeconds(5), "claim");
    assertFalse(worker.stop(Duration.ofMillis(200)));
    waitFor(() -> reason.get() != null, Duration.ofSeconds(2), "cancel");
    assertEquals(CancellationReason.SHUTDOWN, reason.get());
    sleep(200);
    assertTrue(acks(t.get("id").asText()).isEmpty());
  }

  @Test
  void pushReceiverVerifiesThenClaimsFromQueue() {
    ObjectNode t = task("echo", Map.of());
    worker = builder().polling(false).handler("echo", ctx -> Map.of("ok", true)).build().start();
    PushReceiver receiver = new PushReceiver(worker, "whsec");
    byte[] body = "{\"task_id\":\"x\",\"handler_name\":\"echo\",\"queue_name\":\"push-q\"}".getBytes(StandardCharsets.UTF_8);
    String ts = String.valueOf(System.currentTimeMillis() / 1000);
    assertEquals(401, receiver.handle(ts, PushSignature.sign("wrong", ts, body), body));
    assertEquals(401, receiver.handle(null, null, body));
    sleep(200);
    assertTrue(server.requests().isEmpty());
    assertEquals(202, receiver.handle(ts, PushSignature.sign("whsec", ts, body), body));
    waitFor(() -> acks(t.get("id").asText()).size() == 1, Duration.ofSeconds(5), "complete");
    Req poll = server.find("POST", "/workers/tasks/poll/queue").get(0);
    assertEquals("push-q", poll.body.get("queue_name").asText());
    assertEquals(1, poll.body.get("limit").asInt());
    assertNotNull(poll.body.get("worker_id"));
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
