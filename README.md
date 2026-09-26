# Orch8 Java SDK

Java client, worker and push-dispatch verifier for the [Orch8](https://orch8.io)
durable workflow engine. Java 17+, one runtime dependency (Jackson), built on
the JDK's `java.net.http.HttpClient`. Works well from Kotlin: no checked
exceptions, handlers are functional interfaces, and the worker and client are
`AutoCloseable`.

It implements the Orch8 worker wire protocol (contract version 1) and passes
all 17 scenarios of the Orch8 SDK conformance kit ([orch8-io/sdk-contract](https://github.com/orch8-io/sdk-contract)). CI runs the unit
tests; conformance runs from a checkout of the kit next to this repo.

## Install

### Today: Orch8 Maven repository

`0.1.0` is published to the Orch8 Maven repository hosted on GitHub
([orch8-io/maven](https://github.com/orch8-io/maven)). The jars are the same
bytes as the [GitHub release](https://github.com/orch8-io/sdk-java/releases/tag/v0.1.0).

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://raw.githubusercontent.com/orch8-io/maven/main")
}

dependencies {
    implementation("io.orch8:orch8-sdk:0.1.0")
}
```

Maven:

```xml
<repositories>
  <repository>
    <id>orch8</id>
    <url>https://raw.githubusercontent.com/orch8-io/maven/main</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.orch8</groupId>
  <artifactId>orch8-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

You can also download the jar, sources jar, javadoc jar and pom from the
[GitHub release](https://github.com/orch8-io/sdk-java/releases/tag/v0.1.0),
or build from source with `./mvnw install`.

### Once on Maven Central

The same coordinates (`io.orch8:orch8-sdk:0.1.0`) with no extra repository.
The release workflow publishes there automatically once the Central Portal
secrets are configured.

## Client

```java
import io.orch8.sdk.*;
import io.orch8.sdk.model.*;

Orch8Client client = Orch8Client.builder()
    .baseUrl("http://localhost:8080/api/v1")   // the /api/v1 base
    .apiKey(System.getenv("ORCH8_API_KEY"))     // sent as x-api-key
    .tenantId("acme")                           // sent as x-tenant-id
    .build();

// Sequences: the definition can be a Map, JsonNode or your own POJO
CreateSequenceResponse seq = client.sequences().create(Map.of(
    "tenant_id", "acme", "namespace", "default", "name", "onboarding",
    "blocks", List.of(Map.of("type", "step", "id", "s1", "handler", "send_email", "params", Map.of()))));

// Instances
CreateInstanceResponse inst = client.instances().create(
    CreateInstanceRequest.builder(seq.id(), "acme", "default")
        .data(Map.of("user", "u1"))
        .idempotencyKey("signup-u1")
        .build());
boolean replay = inst.deduplicated();
client.instances().signal(inst.id(), SignalType.custom("approve"), Map.of("by", "alice"));
client.instances().cancel(inst.id());
List<Instance> running = client.instances().list(Query.create().state("running").limit(50));

// Jobs
Job job = client.jobs().enqueue(EnqueueJobRequest.builder("send_email", Map.of("to", "a@example.com"))
    .queue("emails").priority(5)
    .retry(RetryPolicy.of(4, 500, 10_000))
    .idempotencyKey("welcome-a")
    .build());
client.jobs().get(job.id());
client.jobs().cancel(job.id());   // Optional<Job>, empty on 204
```

Unset optional request fields are left out of the JSON, not sent as `null`.
Response fields the SDK doesn't model yet stay readable through
`obj.extra()` / `obj.extra("field")`. For endpoints the SDK doesn't wrap, use
`client.request(method, path, query, body[, Type.class])`.

**Retries.** `GET`/`HEAD` are retried on `408/425/429/500/502/503/504` and on
connection errors, with exponential backoff (`maxAttempts`, default 3;
`retryBaseDelay`, default 250 ms). `POST`/`DELETE` are sent exactly once.

**Errors.** All exceptions are unchecked and extend `Orch8Exception`:

| Type | When |
|---|---|
| `Orch8ApiException` (`status()`, `code()`, `getMessage()`, `requestId()`, `details()`, `kind()`) | any 4xx/5xx |
| `InvalidArgumentException` / `UnauthorizedException` / `ForbiddenException` / `NotFoundException` / `ConflictException` / `PayloadTooLargeException` / `UnprocessableException` / `RateLimitedException` / `ServerException` | 400 / 401 / 403 / 404 / 409 / 413 / 422 / 429 / 5xx |
| `Orch8TransportException` | no response (connection refused, timeout, ...) |

`code` and `message` come from the engine's envelope,
`{"error": {"code", "message", "request_id"}}`.

## Worker

```java
import io.orch8.sdk.worker.*;

Orch8Worker worker = Orch8Worker.builder(client)
    .handler("send_email", ctx -> {
      Email e = ctx.params(Email.class);
      if (e.to() == null) throw new NonRetryableTaskException("missing recipient");
      return Map.of("message_id", mailer.send(e));   // merged into context.data
    })
    .handler("import_rows", ctx -> {
      JsonNode cp = ctx.resumeCheckpoint();        // resume after a crash or retry
      int page = cp == null ? 0 : cp.get("page").asInt();
      while (page < total && !ctx.isCancelled()) {
        importPage(page++);
        ctx.checkpoint(Map.of("page", page));      // CAS sequence tracked for you
      }
      return Map.of("pages", page);
    })
    .concurrency(10)                              // default 10
    .pollInterval(Duration.ofSeconds(1))          // default 1 s
    .heartbeatInterval(Duration.ofSeconds(15))    // default 15 s, capped by the server hint
    .queue("emails")                              // optional named queue
    .version("1.4.2")                             // optional, for version pins
    .shutdownTimeout(Duration.ofSeconds(30))      // drain timeout
    .build()
    .registerShutdownHook();                      // graceful stop on SIGTERM

worker.run();   // blocks; or start() + stop()/close()
```

Kotlin:

```kotlin
val worker = Orch8Worker.builder(client)
    .handler("echo") { ctx -> mapOf("echo" to ctx.params()) }
    .build()
```

What the worker does for you (the protocol rules are in brackets):

- **Concurrency.** Slots are reserved before a poll is sent, so `limit`
  never exceeds free capacity, even with one poll loop per handler (P11).
  A full worker doesn't poll.
- **Cadence.** After an empty poll it waits at least `poll_after_ms` (P8).
  When a poll fails it backs off exponentially, starting from the poll
  interval and capped at 30 s (§7.2).
- **Heartbeats.** Every in-flight task is heartbeated at
  `min(configured, heartbeat_interval_secs, lease_secs / 2)` (P9).
- **Lease loss.** If a heartbeat or checkpoint gets a 404/409, the worker
  stops heartbeating that task, cancels the handler and never sends
  `complete`/`fail` for it. `checkpoint()` throws `LeaseLostException` (L2/L3).
- **Acks.** `complete` is retried with the identical body on 5xx/429 or a
  transport error. On a 404/409 it isn't retried and isn't turned into a
  failure (K3/L3). `retryable` is always sent explicitly (F1).
- **Error classification (F4).**
  - `RetryableTaskException`: retryable.
  - `NonRetryableTaskException`: permanent.
  - Any other exception: retryable.
  - Local `timeout_ms` expiry: retryable, and the handler is cancelled.
- **Cancellation.** `ctx.cancellation()` is a `CancellationToken`. Use
  `isCancelled()`, `reason()` (`LEASE_LOST | TIMEOUT | SHUTDOWN`),
  `sleep(ms)` (a sleep that cancellation interrupts), `onCancel(...)` or
  `throwIfCancelled()`. The handler thread is also interrupted.
- **Graceful shutdown.** `stop(timeout)` stops polling at once and keeps
  heartbeating in-flight tasks until they finish and ack. Tasks still running
  at the deadline are cancelled and left unacked; the engine reclaims them
  once their lease expires (§7.7).
- **Threads.** Virtual threads on Java 21+ (`virtualThreads(false)` to opt
  out), platform threads on 17.

## Push dispatch receiver

A push is only a signed wake-up. The receiver verifies it, answers `202`, and
then claims through `POST /workers/tasks/poll/queue`.

```java
Orch8Worker worker = Orch8Worker.builder(client)
    .handler("render", ctx -> render(ctx.params()))
    .polling(false)            // push-only: no background poll loops
    .build()
    .start();
PushReceiver receiver = new PushReceiver(worker, System.getenv("ORCH8_PUSH_SECRET"));

// In any HTTP framework: pass the RAW body bytes.
int status = receiver.handle(
    request.getHeader("X-Orch8-Timestamp"),
    request.getHeader("X-Orch8-Signature"),
    rawBodyBytes);             // 202 accepted | 401 bad signature | 400 bad envelope
```

Only need the verifier?

```java
boolean ok = PushSignature.verify(secret, timestampHeader, signatureHeader, rawBody);
PushSignature.verify(secret, ts, sig, rawBody, /* now */ null, /* toleranceSecs */ 300);
PushSignature.verifyOrThrow(secret, ts, sig, rawBody, null, 300); // throws PushSignatureException
```

The verifier checks `sha256=` followed by 64 hex digits over
`"<timestamp>." + rawBody`, compares in constant time, and rejects
timestamps outside the tolerance window (default ±300 s).

## Building and testing

```bash
export JAVA_HOME=/path/to/jdk-17   # any JDK 17+
./mvnw test                        # unit tests (JUnit 5, JDK HttpServer fakes)
```

### Conformance kit (local only)

Clone [orch8-io/sdk-contract](https://github.com/orch8-io/sdk-contract) next to this repo, then:

```bash
./mvnw -q -DskipTests test-compile   # builds classes + target/classpath.txt
cd ../sdk-contract
node conformance/run.mjs --adapter "$PWD/../sdk-java/bin/conformance"
```

The adapter (`src/test/java/io/orch8/sdk/conformance/Adapter.java`) uses only
the public API. `bin/conformance` `exec`s the JVM directly so SIGTERM reaches
it.

## Releasing

Push a `vX.Y.Z` tag matching the pom version. `.github/workflows/release.yml`
builds the jar, sources and javadoc jars and attaches them with the pom to a
GitHub Release. It also deploys to Maven Central through the Sonatype Central
Portal when these repository secrets exist (otherwise that job is skipped):
`MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` (a Central Portal user
token), `MAVEN_GPG_PRIVATE_KEY` (ASCII-armored) and `MAVEN_GPG_PASSPHRASE`.
The `io.orch8` namespace must be verified in the Central Portal first.

## License

MIT
