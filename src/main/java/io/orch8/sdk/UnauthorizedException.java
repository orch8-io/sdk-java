package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 401 Unauthorized. */
public class UnauthorizedException extends Orch8ApiException {
  public UnauthorizedException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
