package io.orch8.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The engine answered with a 4xx/5xx status. Carries the status and the fields
 * of the error envelope {@code {"error": {"code", "message", "request_id", "details"}}}.
 * Use {@link #kind()} or the subclasses ({@link NotFoundException}, ...) to branch.
 */
public class Orch8ApiException extends Orch8Exception {
  private static final java.util.Set<Integer> RETRYABLE =
      java.util.Set.of(408, 425, 429, 500, 502, 503, 504);

  private final int status;
  private final String code;
  private final String requestId;
  private final JsonNode details;
  private final String rawBody;

  public Orch8ApiException(int status, String code, String message, String requestId,
                           JsonNode details, String rawBody) {
    super(message == null || message.isEmpty() ? "HTTP " + status : message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.details = details;
    this.rawBody = rawBody;
  }

  /** HTTP status code. */
  public int status() {
    return status;
  }

  /** Engine error code (e.g. {@code not_found}); {@code null} when the body had no envelope. */
  public String code() {
    return code;
  }

  /** Request id from the envelope or the {@code x-request-id} response header; may be {@code null}. */
  public String requestId() {
    return requestId;
  }

  /** Optional structured details from the envelope; may be {@code null}. */
  public JsonNode details() {
    return details;
  }

  /** The raw response body (possibly empty). */
  public String rawBody() {
    return rawBody;
  }

  public ErrorKind kind() {
    return ErrorKind.forStatus(status);
  }

  /** True for statuses the transport policy treats as transient (408/425/429/5xx gateway family). */
  public boolean isRetryable() {
    return RETRYABLE.contains(status);
  }

  /** Builds the most specific subclass for {@code status}. */
  public static Orch8ApiException of(int status, String code, String message, String requestId,
                                     JsonNode details, String rawBody) {
    switch (ErrorKind.forStatus(status)) {
      case INVALID_ARGUMENT: return new InvalidArgumentException(status, code, message, requestId, details, rawBody);
      case UNAUTHORIZED: return new UnauthorizedException(status, code, message, requestId, details, rawBody);
      case FORBIDDEN: return new ForbiddenException(status, code, message, requestId, details, rawBody);
      case NOT_FOUND: return new NotFoundException(status, code, message, requestId, details, rawBody);
      case CONFLICT: return new ConflictException(status, code, message, requestId, details, rawBody);
      case PAYLOAD_TOO_LARGE: return new PayloadTooLargeException(status, code, message, requestId, details, rawBody);
      case UNPROCESSABLE: return new UnprocessableException(status, code, message, requestId, details, rawBody);
      case RATE_LIMITED: return new RateLimitedException(status, code, message, requestId, details, rawBody);
      case SERVER: return new ServerException(status, code, message, requestId, details, rawBody);
      default: return new Orch8ApiException(status, code, message, requestId, details, rawBody);
    }
  }
}
