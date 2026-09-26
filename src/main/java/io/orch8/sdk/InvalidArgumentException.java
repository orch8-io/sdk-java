package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 400 Bad Request (invalid_argument). */
public class InvalidArgumentException extends Orch8ApiException {
  public InvalidArgumentException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
