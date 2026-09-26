package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.orch8.sdk.internal.Json;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Body of {@code POST /jobs}. Unset optional fields are omitted (never sent as {@code null}). */
public final class EnqueueJobRequest extends ApiObject {
  @JsonProperty("handler") private String handler;
  @JsonProperty("payload") private JsonNode payload;
  @JsonProperty("queue") private String queue;
  @JsonProperty("priority") private Integer priority;
  @JsonProperty("retry") private RetryPolicy retry;
  @JsonProperty("delay_ms") private Long delayMs;
  @JsonProperty("run_at") private String runAt;
  @JsonProperty("idempotency_key") private String idempotencyKey;
  @JsonProperty("metadata") private JsonNode metadata;

  private EnqueueJobRequest() {}

  /** {@code payload} may be any Jackson-serializable value; {@code null} is sent as {@code {}}. */
  public static Builder builder(String handler, Object payload) {
    return new Builder(handler, payload);
  }

  public String handler() { return handler; }
  public JsonNode payload() { return payload; }
  public String queue() { return queue; }
  public Integer priority() { return priority; }
  public RetryPolicy retry() { return retry; }
  public Long delayMs() { return delayMs; }
  /** RFC 3339 timestamp, as sent. */
  public String runAt() { return runAt; }
  public String idempotencyKey() { return idempotencyKey; }
  public JsonNode metadata() { return metadata; }

  /** Builder for {@link EnqueueJobRequest}. */
  public static final class Builder {
    private final EnqueueJobRequest r = new EnqueueJobRequest();

    private Builder(String handler, Object payload) {
      r.handler = Objects.requireNonNull(handler, "handler");
      r.payload = payload == null ? Json.mapper().createObjectNode() : Json.toNode(payload);
    }

    public Builder queue(String queue) { r.queue = queue; return this; }
    public Builder priority(Integer priority) { r.priority = priority; return this; }
    public Builder retry(RetryPolicy retry) { r.retry = retry; return this; }
    public Builder delayMs(Long delayMs) { r.delayMs = delayMs; return this; }
    public Builder runAt(OffsetDateTime runAt) { r.runAt = runAt == null ? null : runAt.toString(); return this; }
    /** Raw RFC 3339 string, sent verbatim. */
    public Builder runAt(String runAt) { r.runAt = runAt; return this; }
    public Builder idempotencyKey(String key) { r.idempotencyKey = key; return this; }
    public Builder metadata(Object metadata) { r.metadata = Json.toNode(metadata); return this; }

    /** Sets an arbitrary extra wire field (ignored when {@code value} is {@code null}). */
    public Builder field(String name, Object value) {
      if (value != null) {
        r.putExtra(name, Json.toNode(value));
      }
      return this;
    }

    public EnqueueJobRequest build() { return r; }
  }
}
