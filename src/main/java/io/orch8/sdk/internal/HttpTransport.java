package io.orch8.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.orch8.sdk.InvalidPathException;
import io.orch8.sdk.Orch8ApiException;
import io.orch8.sdk.Orch8Config;
import io.orch8.sdk.Orch8Exception;
import io.orch8.sdk.Orch8TransportException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON-over-HTTP transport. Safe methods (GET/HEAD) are retried on transient
 * statuses and connection errors with exponential backoff; unsafe methods are
 * sent exactly once (fixtures/transport.json). Internal API.
 */
public final class HttpTransport {
  private static final Set<String> SAFE = Set.of("GET", "HEAD");

  private final Orch8Config config;
  private final HttpClient http;
  private final String base;

  public HttpTransport(Orch8Config config) {
    this.config = config;
    this.http = config.httpClient() != null
        ? config.httpClient()
        : HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(config.connectTimeout())
            .build();
    String b = config.baseUrl();
    this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
  }

  public Orch8Config config() {
    return config;
  }

  /** Sends a request, retrying only when the method is safe. Returns {@code null} for an empty body. */
  public JsonNode send(String method, String path, Map<String, ?> query, Object body) {
    String m = method.toUpperCase(Locale.ROOT);
    int attempts = SAFE.contains(m) ? Math.max(1, config.maxAttempts()) : 1;
    for (int attempt = 1; ; attempt++) {
      try {
        return sendOnce(m, path, query, body);
      } catch (Orch8TransportException | Orch8ApiException e) {
        if (attempt >= attempts || !isRetryable(e)) {
          throw e;
        }
        long delay = config.retryBaseDelay().toMillis() << Math.min(attempt - 1, 20);
        sleep(delay);
      }
    }
  }

  /** Sends exactly one request (no retries). */
  public JsonNode sendOnce(String method, String path, Map<String, ?> query, Object body) {
    validatePath(path);
    String url = base + path + UrlEncoding.query(query);
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
        .timeout(config.requestTimeout())
        .header("accept", "application/json");
    if (config.apiKey() != null) {
      rb.header("x-api-key", config.apiKey());
    }
    if (config.tenantId() != null) {
      rb.header("x-tenant-id", config.tenantId());
    }
    if (config.userAgent() != null) {
      rb.header("user-agent", config.userAgent());
    }
    for (Map.Entry<String, String> h : config.headers().entrySet()) {
      rb.header(h.getKey(), h.getValue());
    }
    if (body != null) {
      String json = body instanceof JsonNode ? body.toString() : Json.write(body);
      rb.header("content-type", "application/json");
      rb.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    } else {
      rb.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> res;
    try {
      res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new Orch8TransportException(method + " " + path + ": " + describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Orch8TransportException(method + " " + path + ": interrupted", e);
    }
    String text = res.body() == null ? "" : res.body();
    JsonNode json = null;
    if (!text.isBlank()) {
      try {
        json = Json.mapper().readTree(text);
      } catch (IOException e) {
        json = res.statusCode() < 400 ? TextNode.valueOf(text) : null;
      }
    }
    if (res.statusCode() >= 400) {
      throw toApiException(res.statusCode(), json, text, res.headers().firstValue("x-request-id").orElse(null));
    }
    return json;
  }

  static Orch8ApiException toApiException(int status, JsonNode json, String raw, String headerRequestId) {
    String code = null;
    String message = null;
    String requestId = headerRequestId;
    JsonNode details = null;
    JsonNode env = json != null && json.isObject() ? json.get("error") : null;
    if (env != null && env.isObject()) {
      code = textOrNull(env.get("code"));
      message = textOrNull(env.get("message"));
      String rid = textOrNull(env.get("request_id"));
      if (rid != null) {
        requestId = rid;
      }
      details = env.get("details");
    } else if (env != null && env.isTextual()) {
      message = env.asText();
    }
    return Orch8ApiException.of(status, code, message, requestId, details, raw);
  }

  private static String textOrNull(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  public static boolean isRetryable(RuntimeException e) {
    if (e instanceof Orch8TransportException) {
      return true;
    }
    return e instanceof Orch8ApiException && ((Orch8ApiException) e).isRetryable();
  }

  private static void validatePath(String path) {
    if (path == null || !path.startsWith("/") || path.startsWith("//")) {
      throw new InvalidPathException("invalid_path: request path must be absolute and not protocol-relative: " + path);
    }
    if (path.contains("://")) {
      throw new InvalidPathException("invalid_path: request path must not contain a scheme: " + path);
    }
  }

  private static String describe(IOException e) {
    String msg = e.getMessage();
    return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Orch8Exception("interrupted while backing off", e);
    }
  }
}
