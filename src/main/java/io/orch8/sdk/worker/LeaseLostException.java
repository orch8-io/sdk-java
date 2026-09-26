package io.orch8.sdk.worker;

import io.orch8.sdk.Orch8Exception;

/**
 * The task's lease is gone (a mutation returned 404/409). Thrown from
 * {@link TaskContext#checkpoint(Object)}; the worker never acknowledges a task
 * after this.
 */
public class LeaseLostException extends Orch8Exception {
  private final String taskId;

  public LeaseLostException(String taskId, String message) {
    super("lease lost for task " + taskId + ": " + message);
    this.taskId = taskId;
  }

  public String taskId() {
    return taskId;
  }
}
