package io.orch8.sdk;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable connection settings shared by {@link Orch8Client} and the worker.
 *
 * <p>{@code baseUrl} is the versioned API base, e.g. {@code http://localhost:8080/api/v1}.
 */
public final class Orch8Config {
  /** Default base URL (the canonical {@code /api/v1} base of a local engine). */
  public static final String DEFAULT_BASE_URL = "http://localhost:8080/api/v1";

  private final String baseUrl;
  private final String apiKey;
  private final String tenantId;
  private final Duration requestTimeout;
  private final Duration connectTimeout;
  private final int maxAttempts;
  private final Duration retryBaseDelay;
  private final HttpClient httpClient;
  private final String userAgent;
  private final Map<String, String> headers;

  private Orch8Config(Builder b) {
    this.baseUrl = Objects.requireNonNull(b.baseUrl, "baseUrl");
    this.apiKey = b.apiKey;
    this.tenantId = b.tenantId;
    this.requestTimeout = b.requestTimeout;
    this.connectTimeout = b.connectTimeout;
    this.maxAttempts = b.maxAttempts;
    this.retryBaseDelay = b.retryBaseDelay;
    this.httpClient = b.httpClient;
    this.userAgent = b.userAgent;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder pre-populated from {@code ORCH8_BASE_URL}, {@code ORCH8_API_KEY} and
   * {@code ORCH8_TENANT_ID} (unset variables keep the defaults).
   */
  public static Builder fromEnv() {
    Builder b = new Builder();
    String url = System.getenv("ORCH8_BASE_URL");
    if (url != null && !url.isEmpty()) {
      b.baseUrl(url);
    }
    b.apiKey(emptyToNull(System.getenv("ORCH8_API_KEY")));
    b.tenantId(emptyToNull(System.getenv("ORCH8_TENANT_ID")));
    return b;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  public String baseUrl() {
    return baseUrl;
  }

  /** May be {@code null} (engine running with {@code --insecure}). */
  public String apiKey() {
    return apiKey;
  }

  /** May be {@code null}; SDKs should normally always send a tenant. */
  public String tenantId() {
    return tenantId;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  /** Max attempts for safe (GET/HEAD) requests; unsafe requests are never replayed. */
  public int maxAttempts() {
    return maxAttempts;
  }

  public Duration retryBaseDelay() {
    return retryBaseDelay;
  }

  /** Custom {@link HttpClient}, or {@code null} for the SDK default. */
  public HttpClient httpClient() {
    return httpClient;
  }

  public String userAgent() {
    return userAgent;
  }

  public Map<String, String> headers() {
    return headers;
  }

  public Builder toBuilder() {
    Builder b = new Builder()
        .baseUrl(baseUrl).apiKey(apiKey).tenantId(tenantId)
        .requestTimeout(requestTimeout).connectTimeout(connectTimeout)
        .maxAttempts(maxAttempts).retryBaseDelay(retryBaseDelay)
        .httpClient(httpClient).userAgent(userAgent);
    headers.forEach(b::header);
    return b;
  }

  /** Mutable builder for {@link Orch8Config}. */
  public static final class Builder {
    private String baseUrl = DEFAULT_BASE_URL;
    private String apiKey;
    private String tenantId;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(10);
    private int maxAttempts = 3;
    private Duration retryBaseDelay = Duration.ofMillis(250);
    private HttpClient httpClient;
    private String userAgent = "orch8-sdk-java/0.1.0";
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Builder() {}

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
      return this;
    }

    public Builder maxAttempts(int maxAttempts) {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be >= 1");
      }
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder retryBaseDelay(Duration retryBaseDelay) {
      this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay, "retryBaseDelay");
      return this;
    }

    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public Builder userAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /** Adds a static header sent with every request (e.g. {@code x-request-id} prefixing proxies). */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name), Objects.requireNonNull(value));
      return this;
    }

    public Orch8Config build() {
      return new Orch8Config(this);
    }
  }
}
