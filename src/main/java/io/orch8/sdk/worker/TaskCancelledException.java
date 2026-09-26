package io.orch8.sdk.worker;

import io.orch8.sdk.Orch8Exception;

/** Thrown by {@link CancellationToken#throwIfCancelled()}. */
public class TaskCancelledException extends Orch8Exception {
  private final CancellationReason reason;

  public TaskCancelledException(CancellationReason reason) {
    super("task cancelled: " + reason);
    this.reason = reason;
  }

  public CancellationReason reason() {
    return reason;
  }
}
