package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 422 Unprocessable Entity. */
public class UnprocessableException extends Orch8ApiException {
  public UnprocessableException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
