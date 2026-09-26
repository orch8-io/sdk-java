package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 413 Payload Too Large. */
public class PayloadTooLargeException extends Orch8ApiException {
  public PayloadTooLargeException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
