package io.orch8.sdk.worker;

/**
 * Handles one claimed task. The return value becomes the step output (object
 * keys are merged into {@code context.data}); {@code null} is sent as {@code {}}.
 * Usable as a Java or Kotlin lambda: {@code ctx -> Map.of("ok", true)}.
 *
 * <p>Throw {@link RetryableTaskException} / {@link NonRetryableTaskException} to
 * classify failures; any other exception is reported as retryable.
 */
@FunctionalInterface
public interface TaskHandler {
  Object handle(TaskContext ctx) throws Exception;
}
