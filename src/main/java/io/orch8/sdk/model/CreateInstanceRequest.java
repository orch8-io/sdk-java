package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.orch8.sdk.internal.Json;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Body of {@code POST /instances}. Unset optional fields are omitted on the
 * wire. Use {@link Builder#field(String, Object)} for fields this SDK version
 * does not model yet.
 */
public final class CreateInstanceRequest extends ApiObject {
  @JsonProperty("sequence_id") private String sequenceId;
  @JsonProperty("tenant_id") private String tenantId;
  @JsonProperty("namespace") private String namespace;
  @JsonProperty("context") private JsonNode context;
  @JsonProperty("idempotency_key") private String idempotencyKey;
  @JsonProperty("priority") private String priority;
  @JsonProperty("metadata") private JsonNode metadata;
  @JsonProperty("concurrency_key") private String concurrencyKey;
  @JsonProperty("max_concurrency") private Integer maxConcurrency;
  @JsonProperty("next_fire_at") private OffsetDateTime nextFireAt;
  @JsonProperty("dry_run") private Boolean dryRun;

  private CreateInstanceRequest() {}

  public static Builder builder(String sequenceId, String tenantId, String namespace) {
    return new Builder(sequenceId, tenantId, namespace);
  }

  public String sequenceId() { return sequenceId; }
  public String tenantId() { return tenantId; }
  public String namespace() { return namespace; }
  public JsonNode context() { return context; }
  public String idempotencyKey() { return idempotencyKey; }
  public String priority() { return priority; }
  public JsonNode metadata() { return metadata; }
  public String concurrencyKey() { return concurrencyKey; }
  public Integer maxConcurrency() { return maxConcurrency; }
  public OffsetDateTime nextFireAt() { return nextFireAt; }
  public Boolean dryRun() { return dryRun; }

  /** Builder for {@link CreateInstanceRequest}. */
  public static final class Builder {
    private final CreateInstanceRequest r = new CreateInstanceRequest();

    private Builder(String sequenceId, String tenantId, String namespace) {
      r.sequenceId = Objects.requireNonNull(sequenceId, "sequenceId");
      r.tenantId = Objects.requireNonNull(tenantId, "tenantId");
      r.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** Execution context, e.g. {@code Map.of("data", Map.of("user", "u1"))}. */
    public Builder context(Object context) { r.context = Json.toNode(context); return this; }

    /** Convenience for {@code context({"data": data})}. */
    public Builder data(Object data) {
      r.context = Json.mapper().createObjectNode().set("data", Json.toNode(data));
      return this;
    }

    public Builder idempotencyKey(String key) { r.idempotencyKey = key; return this; }
    /** {@code Low|Normal|High|Critical}. */
    public Builder priority(String priority) { r.priority = priority; return this; }
    public Builder metadata(Object metadata) { r.metadata = Json.toNode(metadata); return this; }
    public Builder concurrencyKey(String key) { r.concurrencyKey = key; return this; }
    public Builder maxConcurrency(Integer max) { r.maxConcurrency = max; return this; }
    public Builder nextFireAt(OffsetDateTime at) { r.nextFireAt = at; return this; }
    public Builder dryRun(Boolean dryRun) { r.dryRun = dryRun; return this; }

    /** Sets an arbitrary extra wire field (ignored when {@code value} is {@code null}). */
    public Builder field(String name, Object value) {
      if (value != null) {
        r.putExtra(name, Json.toNode(value));
      }
      return this;
    }

    public CreateInstanceRequest build() { return r; }
  }
}
