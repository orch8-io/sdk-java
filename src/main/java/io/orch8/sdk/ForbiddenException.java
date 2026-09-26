package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 403 Forbidden (tenant mismatch or missing capability). */
public class ForbiddenException extends Orch8ApiException {
  public ForbiddenException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
