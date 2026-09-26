package io.orch8.sdk.worker;

/** Permanent failure: the step fails without engine retry (a {@code try_catch} may still recover). */
public class NonRetryableTaskException extends TaskException {
  public NonRetryableTaskException(String message) {
    super(message, false);
  }

  public NonRetryableTaskException(String message, Throwable cause) {
    super(message, false, cause);
  }
}
