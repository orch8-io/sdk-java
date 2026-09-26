package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 404 Not Found. */
public class NotFoundException extends Orch8ApiException {
  public NotFoundException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
