package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** 5xx server error. */
public class ServerException extends Orch8ApiException {
  public ServerException(int status, String code, String message, String requestId, JsonNode details, String rawBody) {
    super(status, code, message, requestId, details, rawBody);
  }
}
