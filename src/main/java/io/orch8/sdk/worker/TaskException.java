package io.orch8.sdk.worker;

import io.orch8.sdk.Orch8Exception;

/**
 * A handler failure with an explicit retry classification. Throw
 * {@link RetryableTaskException} or {@link NonRetryableTaskException}; any
 * other exception escaping a handler is reported as retryable.
 */
public class TaskException extends Orch8Exception {
  private final boolean retryable;

  public TaskException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public TaskException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  public boolean isRetryable() {
    return retryable;
  }

  public static TaskException retryable(String message) {
    return new RetryableTaskException(message);
  }

  public static TaskException permanent(String message) {
    return new NonRetryableTaskException(message);
  }
}
