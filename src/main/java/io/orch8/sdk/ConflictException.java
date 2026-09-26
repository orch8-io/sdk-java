package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 409 Conflict (conflict / already_exists; for worker tasks: lease lost). */
public class ConflictException extends Orch8ApiException {
  public ConflictException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
