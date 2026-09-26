package io.orch8.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orch8.sdk.model.CreateInstanceRequest;
import io.orch8.sdk.model.CreateInstanceResponse;
import io.orch8.sdk.model.EnqueueJobRequest;
import io.orch8.sdk.model.Instance;
import io.orch8.sdk.model.Job;
import io.orch8.sdk.model.Query;
import io.orch8.sdk.model.RetryPolicy;
import io.orch8.sdk.model.SignalType;
import io.orch8.sdk.testing.FakeServer;
import io.orch8.sdk.testing.FakeServer.Res;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientTest {
  static final String JOB = "{\"id\":\"job_1\",\"instance_id\":\"inst_1\",\"handler\":\"send_email\","
      + "\"status\":\"scheduled\",\"created_at\":\"2026-09-01T00:00:00Z\",\"run_at\":\"2026-09-01T00:00:00Z\","
      + "\"attempts\":0,\"new_engine_field\":{\"x\":1}}";

  FakeServer server;
  Orch8Client client;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeServer();
    client = Orch8Client.builder().baseUrl(server.baseUrl()).apiKey("k").tenantId("t")
        .retryBaseDelay(Duration.ofMillis(1)).build();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void sendsAuthHeadersAndEncodesPathIdsAsOneSegment() {
    server.handle(r -> new Res(200, JOB));
    client.jobs().get("a/b c");
    FakeServer.Req req = server.requests().get(0);
    assertEquals("/api/v1/jobs/a%2Fb%20c", req.rawPath);
    assertEquals("k", req.header("x-api-key"));
    assertEquals("t", req.header("x-tenant-id"));
  }

  @Test
  void enqueueOmitsUnsetFieldsAndKeepsUnknownResponseFields() {
    server.handle(r -> new Res(201, JOB));
    Job job = client.jobs().enqueue(EnqueueJobRequest.builder("send_email", Map.of("to", "b@example.com")).build());
    assertEquals("{\"handler\":\"send_email\",\"payload\":{\"to\":\"b@example.com\"}}", server.requests().get(0).rawBody);
    assertEquals("job_1", job.id());
    assertEquals("scheduled", job.status());
    assertEquals(1, job.extra("new_engine_field").get("x").asInt());

    client.jobs().enqueue(EnqueueJobRequest.builder("h", null).queue("q").priority(5)
        .retry(RetryPolicy.of(4, 500)).delayMs(250L).idempotencyKey("k1").metadata(Map.of("a", 1)).build());
    String body = server.requests().get(1).rawBody;
    assertTrue(body.contains("\"retry\":{\"max_attempts\":4,\"initial_backoff_ms\":500}"), body);
    assertFalse(body.contains("null"), body);
  }

  @Test
  void listPassesQueryAndToleratesWrappers() {
    server.handle(r -> r.path().equals("/jobs") ? new Res(200, "{\"items\":[" + JOB + "]}") : new Res(200, "[]"));
    List<Job> jobs = client.jobs().list(Query.create().status("scheduled").limit(20));
    assertEquals(1, jobs.size());
    assertEquals("status=scheduled&limit=20", server.requests().get(0).rawQuery);
    assertTrue(client.instances().list(Query.create().namespace("default")).isEmpty());
  }

  @Test
  void cancelAcceptsEmptyBody() {
    server.handle(r -> new Res(204, ""));
    assertTrue(client.jobs().cancel("job_1").isEmpty());
    assertEquals("DELETE", server.requests().get(0).method);
  }

  @Test
  void instancesCreateSignalAndCancel() {
    server.handle(r -> {
      if (r.path().equals("/instances")) {
        return new Res(200, "{\"id\":\"i1\",\"deduplicated\":true}");
      }
      if (r.path().equals("/instances/i1")) {
        return new Res(200, "{\"id\":\"i1\",\"state\":\"running\",\"created_at\":\"2026-09-01T00:00:00.123+02:00\"}");
      }
      return new Res(200, "{\"signal_id\":\"s1\"}");
    });
    CreateInstanceResponse created = client.instances().create(
        CreateInstanceRequest.builder("seq1", "t", "default").data(Map.of("user", "u1")).idempotencyKey("dup").build());
    assertEquals("i1", created.id());
    assertTrue(created.deduplicated());
    assertEquals("{\"sequence_id\":\"seq1\",\"tenant_id\":\"t\",\"namespace\":\"default\","
        + "\"context\":{\"data\":{\"user\":\"u1\"}},\"idempotency_key\":\"dup\"}", server.requests().get(0).rawBody);
    Instance inst = client.instances().get("i1");
    assertEquals("running", inst.state());
    assertEquals(123_000_000, inst.createdAt().getNano());
    assertEquals("s1", client.instances().signal("i1", SignalType.custom("approve"), Map.of("by", "alice")).signalId());
    assertEquals("{\"signal_type\":{\"custom\":\"approve\"},\"payload\":{\"by\":\"alice\"}}", server.requests().get(2).rawBody);
    client.instances().cancel("i1");
    assertEquals("{\"signal_type\":\"cancel\"}", server.requests().get(3).rawBody);
  }

  @Test
  void safeRequestsRetryTransientStatuses() {
    AtomicInteger n = new AtomicInteger();
    server.handle(r -> {
      int i = n.incrementAndGet();
      return i == 1 ? Res.error(429, "rate_limited", "slow down")
          : i == 2 ? Res.error(503, "unavailable", "busy") : new Res(200, JOB);
    });
    assertEquals("job_1", client.jobs().get("job_1").id());
    assertEquals(3, server.requests().size());
  }

  @Test
  void safeRequestsStopAfterMaxAttempts() {
    server.handle(r -> Res.error(503, "unavailable", "down"));
    ServerException e = assertThrows(ServerException.class, () -> client.jobs().get("x"));
    assertEquals(503, e.status());
    assertEquals(ErrorKind.SERVER, e.kind());
    assertEquals(3, server.requests().size());
  }

  @Test
  void unsafeRequestsAreNeverReplayed() {
    server.handle(r -> Res.error(503, "unavailable", "down"));
    assertThrows(ServerException.class, () -> client.jobs().enqueue(EnqueueJobRequest.builder("h", Map.of()).build()));
    assertThrows(ServerException.class, () -> client.jobs().cancel("x"));
    assertEquals(2, server.requests().size());
  }

  @Test
  void errorsAreTypedFromTheEnvelope() {
    server.handle(r -> {
      switch (r.path()) {
        case "/jobs/missing": return new Res(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"not found: job\",\"request_id\":\"rq-1\"}}");
        case "/jobs/conflict": return Res.error(409, "already_exists", "dup");
        case "/jobs/bad": return Res.error(400, "invalid_argument", "bad");
        case "/jobs/nobody": return new Res(502, "<html>bad gateway</html>", "hdr-rid");
        default: return Res.error(422, "unprocessable_entity", "nope");
      }
    });
    NotFoundException nf = assertThrows(NotFoundException.class, () -> client.jobs().get("missing"));
    assertEquals("not_found", nf.code());
    assertEquals("not found: job", nf.getMessage());
    assertEquals("rq-1", nf.requestId());
    assertEquals(ErrorKind.NOT_FOUND, nf.kind());
    ConflictException c = assertThrows(ConflictException.class, () -> client.jobs().get("conflict"));
    assertEquals("already_exists", c.code());
    assertInstanceOf(InvalidArgumentException.class, assertThrows(Orch8ApiException.class, () -> client.jobs().get("bad")));
    assertInstanceOf(UnprocessableException.class, assertThrows(Orch8ApiException.class, () -> client.jobs().get("other")));
    Orch8ApiException gw = assertThrows(ServerException.class, () -> client.jobs().get("nobody"));
    assertNull(gw.code());
    assertEquals("HTTP 502", gw.getMessage());
    assertEquals("hdr-rid", gw.requestId());
  }

  @Test
  void kindForEveryDocumentedStatus() {
    assertEquals(ErrorKind.UNAUTHORIZED, ErrorKind.forStatus(401));
    assertEquals(ErrorKind.FORBIDDEN, ErrorKind.forStatus(403));
    assertEquals(ErrorKind.PAYLOAD_TOO_LARGE, ErrorKind.forStatus(413));
    assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.forStatus(429));
    assertEquals(ErrorKind.API, ErrorKind.forStatus(418));
    assertInstanceOf(UnauthorizedException.class, Orch8ApiException.of(401, null, null, null, null, ""));
    assertInstanceOf(ForbiddenException.class, Orch8ApiException.of(403, null, null, null, null, ""));
    assertInstanceOf(PayloadTooLargeException.class, Orch8ApiException.of(413, null, null, null, null, ""));
    assertInstanceOf(RateLimitedException.class, Orch8ApiException.of(429, null, null, null, null, ""));
  }

  @Test
  void connectionFailureIsATransportErrorAndRetriedForGet() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    Orch8Client dead = Orch8Client.builder().baseUrl("http://127.0.0.1:" + port + "/api/v1")
        .retryBaseDelay(Duration.ofMillis(1)).build();
    Orch8TransportException e = assertThrows(Orch8TransportException.class, () -> dead.jobs().get("x"));
    assertEquals(ErrorKind.TRANSPORT, e.kind());
  }

  @Test
  void protocolRelativePathIsRejected() {
    assertThrows(InvalidPathException.class, () -> client.request("GET", "//untrusted.test/path", null, null));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void emptySuccessBodyMapsToNull() {
    server.handle(r -> new Res(204, ""));
    assertNull(client.request("GET", "/anything", null, null));
  }
}
