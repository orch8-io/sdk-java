package io.orch8.sdk.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.orch8.sdk.model.ApiObject;
import java.time.OffsetDateTime;

/** A task claimed by this worker ({@code WorkerTask} in the protocol). */
public class WorkerTask extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("instance_id") private String instanceId;
  @JsonProperty("block_id") private String blockId;
  @JsonProperty("handler_name") private String handlerName;
  @JsonProperty("queue_name") private String queueName;
  @JsonProperty("params") private JsonNode params;
  @JsonProperty("context") private JsonNode context;
  @JsonProperty("attempt") private int attempt;
  @JsonProperty("timeout_ms") private Long timeoutMs;
  @JsonProperty("state") private String state;
  @JsonProperty("worker_id") private String workerId;
  @JsonProperty("claim_epoch") private long claimEpoch;
  @JsonProperty("resume_checkpoint") private JsonNode resumeCheckpoint;
  @JsonProperty("checkpoint_seq") private long checkpointSeq;
  @JsonProperty("created_at") private OffsetDateTime createdAt;

  protected WorkerTask() {}

  public String id() { return id; }
  public String instanceId() { return instanceId; }
  public String blockId() { return blockId; }
  public String handlerName() { return handlerName; }
  /** {@code null} for the default queue. */
  public String queueName() { return queueName; }
  /** Step parameters (templates resolved); may be a JSON null node. */
  public JsonNode params() { return params; }
  /** Serialized execution context ({@code {data, config, ...}}). */
  public JsonNode context() { return context; }
  /** 0 on first dispatch. */
  public int attempt() { return attempt; }
  /** Deadline in ms measured from {@link #createdAt()}; {@code null} when unbounded. */
  public Long timeoutMs() { return timeoutMs; }
  public String state() { return state; }
  public String workerId() { return workerId; }
  /** Ownership generation echoed on every mutation. */
  public long claimEpoch() { return claimEpoch; }
  /** Last durable checkpoint, or {@code null} when none. */
  public JsonNode resumeCheckpoint() {
    return resumeCheckpoint == null || resumeCheckpoint.isNull() ? null : resumeCheckpoint;
  }
  /** CAS version of the checkpoint as of the claim. */
  public long checkpointSeq() { return checkpointSeq; }
  public OffsetDateTime createdAt() { return createdAt; }
}
