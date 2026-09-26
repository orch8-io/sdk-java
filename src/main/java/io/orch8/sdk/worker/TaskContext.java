package io.orch8.sdk.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.orch8.sdk.Orch8Exception;
import io.orch8.sdk.internal.Json;

/**
 * Per-task view handed to a {@link TaskHandler}: the claimed task, a
 * checkpoint API that tracks the compare-and-swap sequence, and a
 * cancellation signal.
 */
public final class TaskContext {
  private final Orch8Worker worker;
  private final Orch8Worker.TaskState state;

  TaskContext(Orch8Worker worker, Orch8Worker.TaskState state) {
    this.worker = worker;
    this.state = state;
  }

  public WorkerTask task() { return state.task; }
  public String taskId() { return state.task.id(); }
  public String instanceId() { return state.task.instanceId(); }
  public String blockId() { return state.task.blockId(); }
  public String handlerName() { return state.task.handlerName(); }
  public JsonNode params() { return state.task.params(); }
  public JsonNode context() { return state.task.context(); }
  public int attempt() { return state.task.attempt(); }
  /** {@code null} when the task has no deadline. */
  public Long timeoutMs() { return state.task.timeoutMs(); }
  public long claimEpoch() { return state.task.claimEpoch(); }
  public String workerId() { return worker.workerId(); }

  /** Last durable checkpoint from a previous attempt, or {@code null}. */
  public JsonNode resumeCheckpoint() { return state.task.resumeCheckpoint(); }

  /** The expected sequence for the next checkpoint (starts at the task's {@code checkpoint_seq}). */
  public long checkpointSeq() { return state.seq(); }

  /** Converts {@link #params()} into {@code type}. */
  public <T> T params(Class<T> type) {
    return convert(params(), type);
  }

  /** Converts {@link #resumeCheckpoint()} into {@code type}; {@code null} when absent. */
  public <T> T resumeCheckpoint(Class<T> type) {
    return convert(resumeCheckpoint(), type);
  }

  /**
   * Durably stores {@code value} as the task's checkpoint (≤ 256 KiB JSON) and
   * refreshes the lease. The CAS sequence is tracked automatically.
   *
   * @return the new checkpoint sequence
   * @throws LeaseLostException if the engine rejects the write with 404/409
   */
  public long checkpoint(Object value) {
    return worker.checkpoint(state, value);
  }

  public CancellationToken cancellation() { return state.token; }

  public boolean isCancelled() { return state.token.isCancelled(); }

  private static <T> T convert(JsonNode node, Class<T> type) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return Json.mapper().treeToValue(node, type);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new Orch8Exception("cannot convert task JSON to " + type.getSimpleName() + ": " + e.getMessage(), e);
    }
  }
}
