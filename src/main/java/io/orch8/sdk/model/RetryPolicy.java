package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Job retry policy: {@code {max_attempts, initial_backoff_ms, max_backoff_ms?}}. */
public final class RetryPolicy extends ApiObject {
  @JsonProperty("max_attempts") private Integer maxAttempts;
  @JsonProperty("initial_backoff_ms") private Long initialBackoffMs;
  @JsonProperty("max_backoff_ms") private Long maxBackoffMs;

  private RetryPolicy() {}

  public static RetryPolicy of(int maxAttempts, long initialBackoffMs) {
    RetryPolicy p = new RetryPolicy();
    p.maxAttempts = maxAttempts;
    p.initialBackoffMs = initialBackoffMs;
    return p;
  }

  public static RetryPolicy of(int maxAttempts, long initialBackoffMs, long maxBackoffMs) {
    RetryPolicy p = of(maxAttempts, initialBackoffMs);
    p.maxBackoffMs = maxBackoffMs;
    return p;
  }

  public Integer maxAttempts() { return maxAttempts; }
  public Long initialBackoffMs() { return initialBackoffMs; }
  /** May be {@code null}. */
  public Long maxBackoffMs() { return maxBackoffMs; }
}
