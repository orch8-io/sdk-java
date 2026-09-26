package io.orch8.sdk;

/** Coarse classification of an SDK error, derived from the HTTP status. */
public enum ErrorKind {
  INVALID_ARGUMENT("invalid_argument"),
  UNAUTHORIZED("unauthorized"),
  FORBIDDEN("forbidden"),
  NOT_FOUND("not_found"),
  CONFLICT("conflict"),
  PAYLOAD_TOO_LARGE("payload_too_large"),
  UNPROCESSABLE("unprocessable"),
  RATE_LIMITED("rate_limited"),
  SERVER("server"),
  TRANSPORT("transport"),
  API("api");

  private final String wireName;

  ErrorKind(String wireName) {
    this.wireName = wireName;
  }

  /** Stable snake_case name (as used by the conformance kit). */
  public String wireName() {
    return wireName;
  }

  public static ErrorKind forStatus(int status) {
    switch (status) {
      case 400: return INVALID_ARGUMENT;
      case 401: return UNAUTHORIZED;
      case 403: return FORBIDDEN;
      case 404: return NOT_FOUND;
      case 409: return CONFLICT;
      case 413: return PAYLOAD_TOO_LARGE;
      case 422: return UNPROCESSABLE;
      case 429: return RATE_LIMITED;
      default: return status >= 500 && status <= 599 ? SERVER : API;
    }
  }
}
