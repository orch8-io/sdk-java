package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 429 Too Many Requests (rate_limited). */
public class RateLimitedException extends Orch8ApiException {
  public RateLimitedException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
