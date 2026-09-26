package io.orch8.sdk.worker;

/** Transient failure: the engine may retry the step per its retry policy. */
public class RetryableTaskException extends TaskException {
  public RetryableTaskException(String message) {
    super(message, true);
  }

  public RetryableTaskException(String message, Throwable cause) {
    super(message, true, cause);
  }
}
