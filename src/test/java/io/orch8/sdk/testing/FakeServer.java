package io.orch8.sdk.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Minimal scriptable HTTP server built on the JDK's HttpServer. */
public final class FakeServer implements AutoCloseable {
  public static final ObjectMapper M = new ObjectMapper();

  /** A recorded request. */
  public static final class Req {
    public final String method;
    public final String rawPath;
    public final String rawQuery;
    public final Headers headers;
    public final String rawBody;
    public final JsonNode body;
    public final long at = System.currentTimeMillis();

    Req(String method, String rawPath, String rawQuery, Headers headers, String rawBody) {
      this.method = method;
      this.rawPath = rawPath;
      this.rawQuery = rawQuery;
      this.headers = headers;
      this.rawBody = rawBody;
      JsonNode b = null;
      try {
        b = rawBody.isEmpty() ? null : M.readTree(rawBody);
      } catch (IOException ignored) {
        // leave null
      }
      this.body = b;
    }

    /** Path relative to /api/v1. */
    public String path() {
      return rawPath.startsWith("/api/v1") ? rawPath.substring("/api/v1".length()) : rawPath;
    }

    public String header(String name) {
      return headers.getFirst(name);
    }
  }

  /** A scripted response. */
  public static final class Res {
    final int status;
    final String body;
    final String requestId;

    public Res(int status, String body) {
      this(status, body, null);
    }

    public Res(int status, String body, String requestId) {
      this.status = status;
      this.body = body;
      this.requestId = requestId;
    }

    public static Res json(int status, Object body) {
      try {
        return new Res(status, M.writeValueAsString(body));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    public static Res error(int status, String code, String message) {
      return new Res(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"request_id\":null}}");
    }
  }

  private final HttpServer server;
  private final List<Req> requests = new ArrayList<>();
  private volatile Function<Req, Res> handler = r -> new Res(404, "");

  public FakeServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", ex -> {
      try (ex) {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
            ex.getRequestHeaders(), raw);
        synchronized (requests) {
          requests.add(req);
        }
        Res res;
        try {
          res = handler.apply(req);
        } catch (RuntimeException e) {
          res = new Res(500, "");
        }
        if (res.requestId != null) {
          ex.getResponseHeaders().add("x-request-id", res.requestId);
        }
        byte[] out = res.body == null ? new byte[0] : res.body.getBytes(StandardCharsets.UTF_8);
        if (out.length > 0) {
          ex.getResponseHeaders().add("content-type", "application/json");
        }
        ex.sendResponseHeaders(res.status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
          try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
          }
        }
      }
    });
    server.start();
  }

  public FakeServer handle(Function<Req, Res> handler) {
    this.handler = handler;
    return this;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
  }

  public List<Req> requests() {
    synchronized (requests) {
      return new ArrayList<>(requests);
    }
  }

  public List<Req> find(String method, String path) {
    return requests().stream().filter(r -> r.method.equals(method) && r.path().equals(path)).collect(Collectors.toList());
  }

  public static void waitFor(BooleanSupplier cond, Duration timeout, String what) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
