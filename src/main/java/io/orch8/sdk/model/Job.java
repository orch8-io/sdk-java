package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A background job ({@code /jobs}). Unmodelled fields are available via {@link #extra()}. */
public class Job extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("instance_id") private String instanceId;
  @JsonProperty("handler") private String handler;
  @JsonProperty("status") private String status;
  @JsonProperty("queue") private String queue;
  @JsonProperty("priority") private Integer priority;
  @JsonProperty("payload") private JsonNode payload;
  @JsonProperty("metadata") private JsonNode metadata;
  @JsonProperty("idempotency_key") private String idempotencyKey;
  @JsonProperty("created_at") private OffsetDateTime createdAt;
  @JsonProperty("run_at") private OffsetDateTime runAt;

  protected Job() {}

  public String id() { return id; }
  public String instanceId() { return instanceId; }
  public String handler() { return handler; }
  /** e.g. {@code scheduled|running|completed|failed|cancelled} (string for forward compatibility). */
  public String status() { return status; }
  public String queue() { return queue; }
  public Integer priority() { return priority; }
  public JsonNode payload() { return payload; }
  public JsonNode metadata() { return metadata; }
  public String idempotencyKey() { return idempotencyKey; }
  public OffsetDateTime createdAt() { return createdAt; }
  public OffsetDateTime runAt() { return runAt; }
}
