package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orch8.sdk.internal.HttpTransport;
import io.orch8.sdk.internal.Json;
import io.orch8.sdk.internal.UrlEncoding;
import io.orch8.sdk.model.CreateInstanceRequest;
import io.orch8.sdk.model.CreateInstanceResponse;
import io.orch8.sdk.model.CreateSequenceResponse;
import io.orch8.sdk.model.EnqueueJobRequest;
import io.orch8.sdk.model.Instance;
import io.orch8.sdk.model.Job;
import io.orch8.sdk.model.Query;
import io.orch8.sdk.model.Sequence;
import io.orch8.sdk.model.SignalResponse;
import io.orch8.sdk.model.SignalType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed REST client for the Orch8 engine.
 *
 * <pre>{@code
 * Orch8Client client = Orch8Client.builder()
 *     .baseUrl("http://localhost:8080/api/v1")
 *     .apiKey("secret")
 *     .tenantId("acme")
 *     .build();
 * Job job = client.jobs().enqueue(EnqueueJobRequest.builder("send_email", Map.of("to", "a@b.c")).build());
 * }</pre>
 *
 * <p>All methods throw unchecked exceptions: {@link Orch8ApiException} (and its
 * status-specific subclasses) for 4xx/5xx responses and
 * {@link Orch8TransportException} when no response was received. GET requests
 * are retried on transient failures; POST/DELETE are never replayed.
 * Thread-safe; share one instance.
 */
public final class Orch8Client implements AutoCloseable {
  private final HttpTransport transport;
  private final Sequences sequences = new Sequences();
  private final Instances instances = new Instances();
  private final Jobs jobs = new Jobs();

  public Orch8Client(Orch8Config config) {
    this.transport = new HttpTransport(Objects.requireNonNull(config, "config"));
  }

  /** Builder for the connection settings; {@code build()} returns a client. */
  public static Builder builder() {
    return new Builder(Orch8Config.builder());
  }

  /** Client configured from {@code ORCH8_BASE_URL}, {@code ORCH8_API_KEY}, {@code ORCH8_TENANT_ID}. */
  public static Orch8Client fromEnv() {
    return new Orch8Client(Orch8Config.fromEnv().build());
  }

  public Orch8Config config() {
    return transport.config();
  }

  /** The Jackson mapper the SDK uses (e.g. to convert {@code JsonNode} fields into your types). */
  public static ObjectMapper mapper() {
    return Json.mapper();
  }

  public Sequences sequences() {
    return sequences;
  }

  public Instances instances() {
    return instances;
  }

  public Jobs jobs() {
    return jobs;
  }

  /**
   * Escape hatch for endpoints this SDK does not wrap yet. {@code path} is
   * relative to the base URL and must start with {@code /}; {@code query} and
   * {@code body} may be {@code null}. Returns {@code null} for an empty body.
   */
  public JsonNode request(String method, String path, Map<String, ?> query, Object body) {
    return transport.send(method, path, query, body);
  }

  /** Typed variant of {@link #request(String, String, Map, Object)}. */
  public <T> T request(String method, String path, Map<String, ?> query, Object body, Class<T> type) {
    return Json.convert(transport.send(method, path, query, body), type);
  }

  /** Internal: the shared transport (used by the worker). */
  public HttpTransport transport() {
    return transport;
  }

  /** No-op today (the JDK {@code HttpClient} needs no explicit close on 17); kept for try-with-resources. */
  @Override
  public void close() {}

  static String seg(String id) {
    return UrlEncoding.segment(Objects.requireNonNull(id, "id"));
  }

  static Map<String, Object> q(Query query) {
    return query == null ? Collections.emptyMap() : query.toMap();
  }

  <T> List<T> list(String path, Query query, Class<T> type, String... wrapperKeys) {
    JsonNode node = transport.send("GET", path, q(query), null);
    if (node != null && node.isObject()) {
      for (String k : wrapperKeys) {
        if (node.has(k) && node.get(k).isArray()) {
          node = node.get(k);
          break;
        }
      }
    }
    List<T> out = new ArrayList<>();
    if (node == null || node.isNull()) {
      return out;
    }
    if (!node.isArray()) {
      throw new Orch8Exception("expected a JSON array from GET " + path + ", got " + node.getNodeType());
    }
    for (JsonNode item : node) {
      out.add(Json.convert(item, type));
    }
    return out;
  }

  /** {@code /sequences} operations. */
  public final class Sequences {
    private Sequences() {}

    /**
     * Creates a sequence. {@code definition} is any Jackson-serializable value
     * (a {@code Map}, {@code JsonNode} or your own POJO) matching
     * {@code SequenceDefinition}.
     */
    public CreateSequenceResponse create(Object definition) {
      Objects.requireNonNull(definition, "definition");
      return Json.convert(transport.send("POST", "/sequences", null, definition), CreateSequenceResponse.class);
    }

    public Sequence get(String id) {
      return Json.convert(transport.send("GET", "/sequences/" + seg(id), null, null), Sequence.class);
    }

    /** Supported query keys: {@code tenant_id, namespace, limit, offset}. */
    public List<Sequence> list(Query query) {
      return Orch8Client.this.list("/sequences", query, Sequence.class, "items", "sequences");
    }

    public List<Sequence> list() {
      return list(null);
    }
  }

  /** {@code /instances} operations. */
  public final class Instances {
    private Instances() {}

    /** Creates an instance; with an {@code idempotency_key} a replay returns {@code deduplicated() == true}. */
    public CreateInstanceResponse create(CreateInstanceRequest request) {
      Objects.requireNonNull(request, "request");
      return Json.convert(transport.send("POST", "/instances", null, request), CreateInstanceResponse.class);
    }

    public Instance get(String id) {
      return Json.convert(transport.send("GET", "/instances/" + seg(id), null, null), Instance.class);
    }

    /** Supported query keys: {@code tenant_id, namespace, sequence_id, state, limit, offset}. */
    public List<Instance> list(Query query) {
      return Orch8Client.this.list("/instances", query, Instance.class, "items", "instances");
    }

    public List<Instance> list() {
      return list(null);
    }

    /** Sends a signal; {@code payload} may be {@code null} (then omitted). */
    public SignalResponse signal(String id, SignalType type, Object payload) {
      Objects.requireNonNull(type, "type");
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("signal_type", type);
      if (payload != null) {
        body.put("payload", payload);
      }
      JsonNode res = transport.send("POST", "/instances/" + seg(id) + "/signals", null, body);
      return Json.convert(res, SignalResponse.class);
    }

    public SignalResponse signal(String id, SignalType type) {
      return signal(id, type, null);
    }

    /** Equivalent to {@code signal(id, SignalType.CANCEL)}. */
    public SignalResponse cancel(String id) {
      return signal(id, SignalType.CANCEL, null);
    }
  }

  /** {@code /jobs} operations. */
  public final class Jobs {
    private Jobs() {}

    public Job enqueue(EnqueueJobRequest request) {
      Objects.requireNonNull(request, "request");
      return Json.convert(transport.send("POST", "/jobs", null, request), Job.class);
    }

    public Job get(String id) {
      return Json.convert(transport.send("GET", "/jobs/" + seg(id), null, null), Job.class);
    }

    /** Accepts a bare array or an {@code {"items": [...]}} / {@code {"jobs": [...]}} wrapper. */
    public List<Job> list(Query query) {
      return Orch8Client.this.list("/jobs", query, Job.class, "items", "jobs");
    }

    public List<Job> list() {
      return list(null);
    }

    /** Cancels a job. Returns the updated job, or empty when the server answered 204 / no body. */
    public Optional<Job> cancel(String id) {
      JsonNode res = transport.send("DELETE", "/jobs/" + seg(id), null, null);
      return Optional.ofNullable(res != null && res.isObject() ? Json.convert(res, Job.class) : null);
    }
  }

  /** Builds a client; mirrors {@link Orch8Config.Builder}. */
  public static final class Builder {
    private final Orch8Config.Builder config;

    private Builder(Orch8Config.Builder config) {
      this.config = config;
    }

    public Builder baseUrl(String baseUrl) { config.baseUrl(baseUrl); return this; }
    public Builder apiKey(String apiKey) { config.apiKey(apiKey); return this; }
    public Builder tenantId(String tenantId) { config.tenantId(tenantId); return this; }
    public Builder requestTimeout(java.time.Duration d) { config.requestTimeout(d); return this; }
    public Builder connectTimeout(java.time.Duration d) { config.connectTimeout(d); return this; }
    public Builder maxAttempts(int n) { config.maxAttempts(n); return this; }
    public Builder retryBaseDelay(java.time.Duration d) { config.retryBaseDelay(d); return this; }
    public Builder httpClient(java.net.http.HttpClient c) { config.httpClient(c); return this; }
    public Builder userAgent(String ua) { config.userAgent(ua); return this; }
    public Builder header(String name, String value) { config.header(name, value); return this; }

    public Orch8Client build() {
      return new Orch8Client(config.build());
    }
  }
}
