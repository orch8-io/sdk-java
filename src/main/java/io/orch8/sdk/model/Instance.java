package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A workflow instance ({@code TaskInstance} in the OpenAPI schema). */
public class Instance extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("sequence_id") private String sequenceId;
  @JsonProperty("tenant_id") private String tenantId;
  @JsonProperty("namespace") private String namespace;
  @JsonProperty("state") private String state;
  @JsonProperty("priority") private String priority;
  @JsonProperty("context") private JsonNode context;
  @JsonProperty("metadata") private JsonNode metadata;
  @JsonProperty("idempotency_key") private String idempotencyKey;
  @JsonProperty("parent_instance_id") private String parentInstanceId;
  @JsonProperty("next_fire_at") private OffsetDateTime nextFireAt;
  @JsonProperty("created_at") private OffsetDateTime createdAt;
  @JsonProperty("updated_at") private OffsetDateTime updatedAt;

  protected Instance() {}

  public String id() { return id; }
  public String sequenceId() { return sequenceId; }
  public String tenantId() { return tenantId; }
  public String namespace() { return namespace; }
  /** {@code scheduled|running|waiting|paused|completed|failed|cancelled} (kept as a string for forward compatibility). */
  public String state() { return state; }
  /** {@code Low|Normal|High|Critical}. */
  public String priority() { return priority; }
  public JsonNode context() { return context; }
  public JsonNode metadata() { return metadata; }
  public String idempotencyKey() { return idempotencyKey; }
  public String parentInstanceId() { return parentInstanceId; }
  public OffsetDateTime nextFireAt() { return nextFireAt; }
  public OffsetDateTime createdAt() { return createdAt; }
  public OffsetDateTime updatedAt() { return updatedAt; }
}
