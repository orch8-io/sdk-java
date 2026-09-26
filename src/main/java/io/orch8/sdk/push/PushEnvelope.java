package io.orch8.sdk.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.orch8.sdk.Orch8Exception;
import io.orch8.sdk.internal.Json;
import io.orch8.sdk.model.ApiObject;
import java.io.IOException;

/**
 * Push-dispatch body (§8.1). It carries no {@code claim_epoch}: a push is a
 * wake-up, and the receiver must claim via the queue poll endpoint.
 */
public class PushEnvelope extends ApiObject {
  @JsonProperty("task_id") private String taskId;
  @JsonProperty("instance_id") private String instanceId;
  @JsonProperty("block_id") private String blockId;
  @JsonProperty("handler_name") private String handlerName;
  @JsonProperty("queue_name") private String queueName;
  @JsonProperty("params") private JsonNode params;
  @JsonProperty("context") private JsonNode context;
  @JsonProperty("attempt") private Integer attempt;
  @JsonProperty("timeout_ms") private Long timeoutMs;

  protected PushEnvelope() {}

  /** Parses a (verified) raw body. */
  public static PushEnvelope parse(byte[] rawBody) {
    try {
      PushEnvelope env = Json.mapper().readValue(rawBody, PushEnvelope.class);
      if (env == null || env.handlerName == null || env.handlerName.isEmpty()) {
        throw new Orch8Exception("push envelope has no handler_name");
      }
      return env;
    } catch (IOException e) {
      throw new Orch8Exception("invalid push envelope: " + e.getMessage(), e);
    }
  }

  public String taskId() { return taskId; }
  public String instanceId() { return instanceId; }
  public String blockId() { return blockId; }
  public String handlerName() { return handlerName; }
  public String queueName() { return queueName; }
  public JsonNode params() { return params; }
  public JsonNode context() { return context; }
  public Integer attempt() { return attempt; }
  public Long timeoutMs() { return timeoutMs; }
}
