package io.orch8.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orch8.sdk.Orch8ApiException;
import io.orch8.sdk.Orch8Client;
import io.orch8.sdk.Orch8Config;
import io.orch8.sdk.Orch8Exception;
import io.orch8.sdk.internal.HttpTransport;
import io.orch8.sdk.internal.Json;
import io.orch8.sdk.internal.UrlEncoding;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long-poll worker implementing the Orch8 worker protocol (WORKER_PROTOCOL.md).
 *
 * <pre>{@code
 * try (Orch8Worker worker = Orch8Worker.builder(client)
 *     .handler("send_email", ctx -> Map.of("sent", true))
 *     .concurrency(10)
 *     .build()) {
 *   worker.run(); // blocks until stop() / close() from another thread
 * }
 * }</pre>
 *
 * <p>Guarantees: concurrency slots are reserved before a poll is sent (so
 * {@code limit} never exceeds free capacity); {@code poll_after_ms} is
 * honoured; poll errors back off exponentially (cap 30 s); every in-flight
 * task is heartbeated at {@code min(configured, server hint)}; after a 404/409
 * on a heartbeat/checkpoint the task is cancelled and never acknowledged;
 * {@code complete} is retried with an identical body on transient failures;
 * generic handler exceptions are reported as retryable; {@code timeout_ms} is
 * enforced locally; {@link #stop(Duration)} drains in-flight tasks.
 *
 * <p>Uses virtual threads when running on Java 21+, platform threads otherwise.
 */
public final class Orch8Worker implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger("io.orch8.sdk.worker");
  private static final long MAX_POLL_BACKOFF_MS = 30_000;
  private static final int ACK_ATTEMPTS = 5;
  private static final long HEARTBEAT_TICK_MS = 50;

  private static final int NEW = 0;
  private static final int RUNNING = 1;
  private static final int STOPPING = 2;
  private static final int STOPPED = 3;

  private final HttpTransport transport;
  private final Map<String, TaskHandler> handlers;
  private final String workerId;
  private final int concurrency;
  private final long pollIntervalMs;
  private final long configuredHeartbeatMs;
  private final String queue;
  private final String version;
  private final Duration shutdownTimeout;
  private final boolean polling;
  private final boolean virtualThreads;

  private final Object slotLock = new Object();
  private int freeSlots;
  private final Map<String, TaskState> inFlight = new ConcurrentHashMap<>();
  private volatile long heartbeatMs;
  private final AtomicInteger phase = new AtomicInteger(NEW);
  private final CountDownLatch stopSignal = new CountDownLatch(1);
  private final CountDownLatch terminated = new CountDownLatch(1);
  private final List<Future<?>> pollLoops = Collections.synchronizedList(new ArrayList<>());
  private ExecutorService executor;
  private ScheduledExecutorService scheduler;

  private Orch8Worker(Builder b) {
    this.transport = b.client != null ? b.client.transport() : new HttpTransport(b.config);
    if (b.handlers.isEmpty()) {
      throw new IllegalArgumentException("register at least one handler");
    }
    this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(b.handlers));
    this.workerId = b.workerId != null ? b.workerId : defaultWorkerId();
    this.concurrency = b.concurrency;
    this.pollIntervalMs = b.pollInterval.toMillis();
    this.configuredHeartbeatMs = b.heartbeatInterval.toMillis();
    this.heartbeatMs = configuredHeartbeatMs;
    this.queue = b.queue;
    this.version = b.version;
    this.shutdownTimeout = b.shutdownTimeout;
    this.polling = b.polling;
    this.virtualThreads = b.virtualThreads;
    this.freeSlots = concurrency;
  }

  public static Builder builder(Orch8Client client) {
    return new Builder(Objects.requireNonNull(client, "client"), null);
  }

  public static Builder builder(Orch8Config config) {
    return new Builder(null, Objects.requireNonNull(config, "config"));
  }

  public String workerId() {
    return workerId;
  }

  public int concurrency() {
    return concurrency;
  }

  /** Handler names this worker serves. */
  public java.util.Set<String> handlerNames() {
    return handlers.keySet();
  }

  /** Number of tasks currently executing (including ones being acknowledged). */
  public int inFlightCount() {
    return inFlight.size();
  }

  /** Current effective heartbeat interval (configured value capped by the server hint). */
  public Duration heartbeatInterval() {
    return Duration.ofMillis(heartbeatMs);
  }

  public boolean isRunning() {
    return phase.get() == RUNNING;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** Starts polling (and heartbeating) in the background. Idempotent; returns immediately. */
  public synchronized Orch8Worker start() {
    if (!phase.compareAndSet(NEW, RUNNING)) {
      return this;
    }
    executor = newExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("orch8-heartbeat"));
    scheduler.scheduleWithFixedDelay(this::heartbeatTick, HEARTBEAT_TICK_MS, HEARTBEAT_TICK_MS, TimeUnit.MILLISECONDS);
    if (polling) {
      for (String name : handlers.keySet()) {
        pollLoops.add(executor.submit(() -> pollLoop(name)));
      }
    }
    LOG.log(Level.DEBUG, () -> "orch8 worker " + workerId + " started for " + handlers.keySet());
    return this;
  }

  /** Starts the worker and blocks until it has been stopped by another thread (or interrupted). */
  public void run() {
    start();
    if (!awaitTermination()) {
      stop(shutdownTimeout);
    }
  }

  /**
   * Blocks until {@link #stop(Duration)} has finished.
   *
   * @return {@code false} if the calling thread was interrupted first (the interrupt flag is restored)
   */
  public boolean awaitTermination() {
    try {
      terminated.await();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Graceful shutdown: stops polling immediately, keeps heartbeating in-flight
   * tasks and lets them finish and acknowledge for up to {@code drainTimeout}.
   * Tasks still running after that are cancelled ({@link CancellationReason#SHUTDOWN})
   * and left unacknowledged for lease recovery.
   *
   * @return {@code true} if every in-flight task finished within the timeout
   */
  public boolean stop(Duration drainTimeout) {
    int prev = phase.getAndUpdate(p -> p == NEW || p == RUNNING ? STOPPING : p);
    if (prev == NEW) {
      phase.set(STOPPED);
      terminated.countDown();
      return true;
    }
    if (prev != RUNNING) {
      try {
        terminated.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return inFlight.isEmpty();
    }
    stopSignal.countDown();
    long deadline = System.nanoTime() + drainTimeout.toNanos();
    List<Future<?>> loops;
    synchronized (pollLoops) {
      loops = new ArrayList<>(pollLoops);
    }
    for (Future<?> f : loops) {
      long left = deadline - System.nanoTime();
      try {
        f.get(Math.max(0, left), TimeUnit.NANOSECONDS);
      } catch (TimeoutException e) {
        f.cancel(true);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (ExecutionException | CancellationException e) {
        // loop ended abnormally; nothing to drain
      }
    }
    while (!inFlight.isEmpty() && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    boolean drained = inFlight.isEmpty();
    if (!drained) {
      LOG.log(Level.WARNING, () -> "orch8 worker drain timeout: abandoning " + inFlight.size()
          + " task(s) without acknowledgement (they will be reclaimed after lease expiry)");
      for (TaskState st : inFlight.values()) {
        st.abandon();
      }
    }
    scheduler.shutdownNow();
    executor.shutdownNow();
    phase.set(STOPPED);
    terminated.countDown();
    return drained;
  }

  /** Stops with the configured shutdown timeout. */
  public boolean stop() {
    return stop(shutdownTimeout);
  }

  /** Equivalent to {@link #stop()}. */
  @Override
  public void close() {
    stop();
  }

  /**
   * Registers a JVM shutdown hook that gracefully stops this worker (useful
   * for SIGTERM in containers).
   */
  public Orch8Worker registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "orch8-worker-shutdown"));
    return this;
  }

  // ---------------------------------------------------------------------------
  // Polling
  // ---------------------------------------------------------------------------

  private void pollLoop(String handlerName) {
    int failures = 0;
    while (isRunning()) {
      int limit = reserve(concurrency);
      if (limit == 0) {
        waitForSlot();
        continue;
      }
      long delay;
      try {
        PollResult r = pollReserved(handlerName, queue, limit);
        failures = 0;
        delay = r.claimed > 0 ? 0 : Math.max(pollIntervalMs, r.pollAfterMs);
      } catch (RuntimeException e) {
        failures++;
        delay = Math.min(pollIntervalMs * (1L << Math.min(failures, 20)), MAX_POLL_BACKOFF_MS);
        final int f = failures;
        LOG.log(Level.WARNING, () -> "orch8 poll for " + handlerName + " failed (" + f + "): " + e.getMessage());
      }
      if (delay > 0) {
        awaitStop(delay);
      }
    }
  }

  private static final class PollResult {
    final int claimed;
    final long pollAfterMs;

    PollResult(int claimed, long pollAfterMs) {
      this.claimed = claimed;
      this.pollAfterMs = pollAfterMs;
    }
  }

  /**
   * Claims up to {@code wanted} tasks for {@code handlerName} (from
   * {@code queueName} when non-null, else the default poll endpoint) and
   * starts executing them. Used by push receivers, whose wake-up carries no
   * claim. Never requests more tasks than free concurrency slots.
   *
   * @return the number of tasks claimed
   */
  public int pollOnce(String handlerName, String queueName, int wanted) {
    if (phase.get() != RUNNING) {
      throw new IllegalStateException("worker is not running; call start() first");
    }
    int limit = reserve(wanted);
    if (limit == 0) {
      return 0;
    }
    return pollReserved(handlerName, queueName, limit).claimed;
  }

  /** Asynchronous {@link #pollOnce}; errors are logged. Returns immediately. */
  public void pollOnceAsync(String handlerName, String queueName, int wanted) {
    if (phase.get() != RUNNING) {
      throw new IllegalStateException("worker is not running; call start() first");
    }
    executor.submit(() -> {
      try {
        pollOnce(handlerName, queueName, wanted);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, () -> "orch8 push-triggered claim failed: " + e.getMessage());
      }
    });
  }

  /** Polls with {@code limit} slots already reserved; releases whatever is not used. */
  private PollResult pollReserved(String handlerName, String queueName, int limit) {
    JsonNode res;
    try {
      ObjectNode body = Json.mapper().createObjectNode();
      body.put("handler_name", handlerName);
      body.put("worker_id", workerId);
      body.put("limit", limit);
      if (queueName != null) {
        body.put("queue_name", queueName);
      }
      if (version != null) {
        body.put("version", version);
      }
      String path = queueName != null ? "/workers/tasks/poll/queue" : "/workers/tasks/poll";
      res = transport.sendOnce("POST", path, null, body);
    } catch (RuntimeException e) {
      release(limit);
      throw e;
    }
    List<WorkerTask> tasks = new ArrayList<>();
    JsonNode arr = res == null ? null : res.get("tasks");
    if (arr != null && arr.isArray()) {
      for (JsonNode t : arr) {
        if (tasks.size() >= limit) {
          LOG.log(Level.WARNING, "orch8 engine returned more tasks than requested; extra tasks left for lease recovery");
          break;
        }
        try {
          tasks.add(Json.mapper().treeToValue(t, WorkerTask.class));
        } catch (Exception e) {
          LOG.log(Level.ERROR, () -> "orch8 could not decode claimed task " + t.path("id") + ": " + e.getMessage());
        }
      }
    }
    release(limit - tasks.size());
    long pollAfter = 0;
    if (res != null) {
      updateHints(res);
      JsonNode after = res.get("poll_after_ms");
      pollAfter = after != null && after.isNumber() ? Math.max(0, after.asLong()) : 0;
    }
    for (WorkerTask task : tasks) {
      TaskState st = new TaskState(task);
      inFlight.put(task.id(), st);
      try {
        executor.submit(() -> execute(st));
      } catch (RuntimeException e) {
        inFlight.remove(task.id());
        release(1);
        throw e;
      }
    }
    return new PollResult(tasks.size(), pollAfter);
  }

  private void updateHints(JsonNode res) {
    JsonNode hint = res.get("heartbeat_interval_secs");
    if (hint != null && hint.isNumber() && hint.asLong() > 0) {
      long ms = Math.min(configuredHeartbeatMs, hint.asLong() * 1000);
      JsonNode lease = res.get("lease_secs");
      if (lease != null && lease.isNumber() && lease.asLong() > 0) {
        ms = Math.min(ms, lease.asLong() * 500);
      }
      heartbeatMs = Math.max(100, ms);
    }
  }

  private int reserve(int wanted) {
    synchronized (slotLock) {
      int limit = Math.max(0, Math.min(wanted, freeSlots));
      freeSlots -= limit;
      return limit;
    }
  }

  private void release(int n) {
    if (n <= 0) {
      return;
    }
    synchronized (slotLock) {
      freeSlots += n;
      slotLock.notifyAll();
    }
  }

  private void waitForSlot() {
    synchronized (slotLock) {
      if (freeSlots > 0 || !isRunning()) {
        return;
      }
      try {
        slotLock.wait(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void awaitStop(long ms) {
    try {
      stopSignal.await(ms, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------------

  private void execute(TaskState st) {
    WorkerTask task = st.task;
    try {
      TaskHandler handler = handlers.get(task.handlerName());
      if (handler == null) {
        ack(st, "fail", failBody(st, "no handler registered for " + task.handlerName(), false));
        return;
      }
      TaskContext ctx = new TaskContext(this, st);
      Future<Object> future;
      synchronized (st) {
        if (st.lost || st.abandoned) {
          return;
        }
        future = executor.submit(() -> handler.handle(ctx));
        st.future = future;
      }
      Object output = null;
      Throwable error = null;
      try {
        long deadline = st.deadlineMillis();
        if (deadline > 0) {
          long left = deadline - System.currentTimeMillis();
          output = future.get(Math.max(0, left), TimeUnit.MILLISECONDS);
        } else {
          output = future.get();
        }
      } catch (TimeoutException e) {
        st.token.cancel(CancellationReason.TIMEOUT);
        future.cancel(true);
        error = new RetryableTaskException("task timed out after " + task.timeoutMs() + " ms (timeout_ms exceeded)");
      } catch (ExecutionException e) {
        error = e.getCause() != null ? e.getCause() : e;
      } catch (CancellationException e) {
        error = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return; // forced shutdown: leave for lease recovery
      }
      if (st.lost || st.abandoned || error instanceof LeaseLostException) {
        return;
      }
      if (error != null) {
        ack(st, "fail", failBody(st, messageOf(error), classify(error)));
      } else {
        ObjectNode body = baseBody(st);
        JsonNode out = output == null ? null : Json.toNode(output);
        body.set("output", out == null || out.isNull() ? Json.mapper().createObjectNode() : out);
        ack(st, "complete", body);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, () -> "orch8 worker internal error for task " + task.id() + ": " + e);
    } finally {
      inFlight.remove(task.id());
      release(1);
    }
  }

  static boolean classify(Throwable error) {
    if (error instanceof TaskException) {
      return ((TaskException) error).isRetryable();
    }
    return true; // F4: generic exceptions are transient by default
  }

  private static String messageOf(Throwable error) {
    String msg = error.getMessage();
    if (msg == null || msg.isBlank()) {
      msg = error.getClass().getName();
    }
    return msg;
  }

  private ObjectNode baseBody(TaskState st) {
    ObjectNode body = Json.mapper().createObjectNode();
    body.put("worker_id", workerId);
    body.put("claim_epoch", st.task.claimEpoch());
    return body;
  }

  private ObjectNode failBody(TaskState st, String message, boolean retryable) {
    ObjectNode body = baseBody(st);
    body.put("message", message);
    body.put("retryable", retryable);
    return body;
  }

  /**
   * Sends complete/fail, retrying transport errors and transient statuses with
   * the identical body (K3/F5). 404/409 means the task is settled elsewhere (L3).
   */
  private void ack(TaskState st, String kind, ObjectNode body) {
    st.acking = true;
    for (int attempt = 1; ; attempt++) {
      if (st.abandoned) {
        return;
      }
      try {
        mutate(st, kind, body);
        return;
      } catch (LeaseLostException e) {
        LOG.log(Level.DEBUG, () -> "orch8 " + kind + " rejected, task settled elsewhere: " + e.getMessage());
        return;
      } catch (RuntimeException e) {
        if (!HttpTransport.isRetryable(e) || attempt >= ACK_ATTEMPTS) {
          final int a = attempt;
          LOG.log(Level.WARNING, () -> "orch8 " + kind + " for task " + st.task.id() + " failed after " + a
              + " attempt(s); leaving it for lease recovery: " + e.getMessage());
          return;
        }
        sleepQuietly(Math.min(200L << (attempt - 1), 5000L));
      }
    }
  }

  /** POSTs a task mutation; 404/409 marks the lease lost and throws {@link LeaseLostException}. */
  private JsonNode mutate(TaskState st, String kind, ObjectNode body) {
    String path = "/workers/tasks/" + UrlEncoding.segment(st.task.id()) + "/" + kind;
    try {
      return transport.sendOnce("POST", path, null, body);
    } catch (Orch8ApiException e) {
      if (e.status() == 404 || e.status() == 409) {
        st.markLost();
        throw new LeaseLostException(st.task.id(), e.getMessage());
      }
      throw e;
    }
  }

  long checkpoint(TaskState st, Object value) {
    synchronized (st.checkpointLock) {
      if (st.lost) {
        throw new LeaseLostException(st.task.id(), "lease already lost");
      }
      ObjectNode body = baseBody(st);
      JsonNode cp = Json.toNode(value);
      body.set("checkpoint", cp == null ? Json.mapper().nullNode() : cp);
      body.put("checkpoint_seq", st.seq);
      JsonNode res;
      try {
        st.lastBeatNanos = System.nanoTime();
        res = mutate(st, "heartbeat", body);
      } catch (LeaseLostException e) {
        throw e;
      } catch (RuntimeException e) {
        if (!HttpTransport.isRetryable(e)) {
          throw e;
        }
        // Ambiguous failure: retry once with the same seq; a 409 then is treated as lease loss.
        sleepQuietly(200);
        res = mutate(st, "heartbeat", body);
      }
      JsonNode seq = res == null ? null : res.get("checkpoint_seq");
      st.seq = seq != null && seq.isNumber() ? seq.asLong() : st.seq + 1;
      return st.seq;
    }
  }

  // ---------------------------------------------------------------------------
  // Heartbeats
  // ---------------------------------------------------------------------------

  private void heartbeatTick() {
    try {
      long now = System.nanoTime();
      long interval = TimeUnit.MILLISECONDS.toNanos(heartbeatMs);
      for (TaskState st : inFlight.values()) {
        if (st.lost || st.abandoned || st.acking || st.heartbeating) {
          continue;
        }
        if (now - st.lastBeatNanos < interval) {
          continue;
        }
        st.heartbeating = true;
        st.lastBeatNanos = now;
        try {
          executor.submit(() -> sendHeartbeat(st));
        } catch (RuntimeException e) {
          st.heartbeating = false;
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "orch8 heartbeat tick failed: " + e);
    }
  }

  private void sendHeartbeat(TaskState st) {
    try {
      if (!st.lost && !st.acking) {
        mutate(st, "heartbeat", baseBody(st));
      }
    } catch (LeaseLostException e) {
      LOG.log(Level.WARNING, () -> "orch8 " + e.getMessage() + "; cancelling handler");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> "orch8 heartbeat for task " + st.task.id() + " failed: " + e.getMessage());
    } finally {
      st.heartbeating = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Orch8Exception("interrupted", e);
    }
  }

  private ExecutorService newExecutor() {
    if (virtualThreads) {
      try {
        return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Java < 21: fall through to platform threads
      }
    }
    return Executors.newCachedThreadPool(daemonFactory("orch8-worker"));
  }

  private static ThreadFactory daemonFactory(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  static String defaultWorkerId() {
    String host = System.getenv("HOSTNAME");
    if (host == null || host.isEmpty()) {
      host = System.getenv("COMPUTERNAME");
    }
    if (host == null || host.isEmpty()) {
      try {
        host = InetAddress.getLocalHost().getHostName();
      } catch (Exception e) {
        host = "worker";
      }
    }
    return host + "-" + ProcessHandle.current().pid();
  }

  /** Per-claim state. */
  static final class TaskState {
    final WorkerTask task;
    final CancellationToken token = new CancellationToken();
    final Object checkpointLock = new Object();
    volatile long seq;
    volatile long lastBeatNanos = System.nanoTime();
    volatile boolean lost;
    volatile boolean abandoned;
    volatile boolean acking;
    volatile boolean heartbeating;
    Future<?> future;
    private final long claimedAtMillis = System.currentTimeMillis();

    TaskState(WorkerTask task) {
      this.task = task;
      this.seq = task.checkpointSeq();
    }

    long seq() {
      return seq;
    }

    /** Absolute local deadline in epoch ms, or 0 when unbounded. */
    long deadlineMillis() {
      Long timeout = task.timeoutMs();
      if (timeout == null || timeout <= 0) {
        return 0;
      }
      long start = task.createdAt() != null ? task.createdAt().toInstant().toEpochMilli() : claimedAtMillis;
      return start + timeout;
    }

    void markLost() {
      lost = true;
      cancel(CancellationReason.LEASE_LOST);
    }

    void abandon() {
      abandoned = true;
      cancel(CancellationReason.SHUTDOWN);
    }

    private void cancel(CancellationReason reason) {
      token.cancel(reason);
      Future<?> f;
      synchronized (this) {
        f = future;
      }
      if (f != null) {
        f.cancel(true);
      }
    }
  }

  /** Builder for {@link Orch8Worker}. */
  public static final class Builder {
    private final Orch8Client client;
    private final Orch8Config config;
    private final Map<String, TaskHandler> handlers = new LinkedHashMap<>();
    private String workerId;
    private int concurrency = 10;
    private Duration pollInterval = Duration.ofMillis(1000);
    private Duration heartbeatInterval = Duration.ofSeconds(15);
    private String queue;
    private String version;
    private Duration shutdownTimeout = Duration.ofSeconds(30);
    private boolean polling = true;
    private boolean virtualThreads = true;

    private Builder(Orch8Client client, Orch8Config config) {
      this.client = client;
      this.config = config;
    }

    /** Registers {@code handler} for tasks whose {@code handler_name} is {@code name}. */
    public Builder handler(String name, TaskHandler handler) {
      handlers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(handler, "handler"));
      return this;
    }

    /** Unique per process; defaults to {@code <hostname>-<pid>}. */
    public Builder workerId(String workerId) {
      this.workerId = workerId;
      return this;
    }

    /** Max tasks executing at once across all handlers (default 10). */
    public Builder concurrency(int concurrency) {
      if (concurrency < 1) {
        throw new IllegalArgumentException("concurrency must be >= 1");
      }
      this.concurrency = concurrency;
      return this;
    }

    /** Minimum delay between empty polls per handler, and poll-error backoff base (default 1 s). */
    public Builder pollInterval(Duration pollInterval) {
      this.pollInterval = Objects.requireNonNull(pollInterval);
      return this;
    }

    /** Heartbeat interval (default 15 s); capped by the server's {@code heartbeat_interval_secs}. */
    public Builder heartbeatInterval(Duration heartbeatInterval) {
      this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval);
      return this;
    }

    /** Named queue: polls {@code /workers/tasks/poll/queue}. {@code null} = default queue. */
    public Builder queue(String queue) {
      this.queue = queue == null || queue.isEmpty() ? null : queue;
      return this;
    }

    /** Worker/app version sent on polls (for version pins). */
    public Builder version(String version) {
      this.version = version == null || version.isEmpty() ? null : version;
      return this;
    }

    /** Drain timeout for {@link #stop()} / {@link #close()} (default 30 s). */
    public Builder shutdownTimeout(Duration shutdownTimeout) {
      this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout);
      return this;
    }

    /**
     * Disable the background poll loops (push-only receivers). Claims then
     * happen only through {@link Orch8Worker#pollOnce}. Default {@code true}.
     */
    public Builder polling(boolean polling) {
      this.polling = polling;
      return this;
    }

    /** Use virtual threads when available (Java 21+). Default {@code true}. */
    public Builder virtualThreads(boolean virtualThreads) {
      this.virtualThreads = virtualThreads;
      return this;
    }

    public Orch8Worker build() {
      return new Orch8Worker(this);
    }
  }
}
