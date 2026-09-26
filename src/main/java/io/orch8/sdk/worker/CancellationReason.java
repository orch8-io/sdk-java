package io.orch8.sdk.worker;

/** Why a running handler was asked to stop. */
public enum CancellationReason {
  /** A heartbeat / checkpoint returned 404 or 409: another worker owns the task now. */
  LEASE_LOST,
  /** The task's {@code timeout_ms} deadline passed. */
  TIMEOUT,
  /** The worker's drain timeout elapsed during shutdown. */
  SHUTDOWN
}
