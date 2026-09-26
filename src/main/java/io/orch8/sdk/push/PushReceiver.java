package io.orch8.sdk.push;

import io.orch8.sdk.Orch8Exception;
import io.orch8.sdk.worker.Orch8Worker;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Framework-agnostic push receiver: verify, parse, and trigger an asynchronous
 * queue claim on a running {@link Orch8Worker}. Wire it into any HTTP server:
 *
 * <pre>{@code
 * PushReceiver receiver = new PushReceiver(worker, secret);
 * int status = receiver.handle(req.header("X-Orch8-Timestamp"), req.header("X-Orch8-Signature"), rawBody);
 * respond(status); // 202 accepted, 401 bad signature, 400 bad envelope
 * }</pre>
 *
 * The worker is typically built with {@code polling(false)} and started.
 */
public final class PushReceiver {
  private static final System.Logger LOG = System.getLogger("io.orch8.sdk.push");

  private final Orch8Worker worker;
  private final String secret;
  private final long toleranceSecs;

  public PushReceiver(Orch8Worker worker, String secret) {
    this(worker, secret, PushSignature.DEFAULT_TOLERANCE_SECS);
  }

  public PushReceiver(Orch8Worker worker, String secret, long toleranceSecs) {
    this.worker = Objects.requireNonNull(worker, "worker");
    if (secret == null || secret.isEmpty()) {
      throw new IllegalArgumentException("push secret must be configured");
    }
    this.secret = secret;
    this.toleranceSecs = toleranceSecs;
  }

  /**
   * Handles one push request and returns the HTTP status to answer with:
   * {@code 401} for a bad signature (nothing is claimed), {@code 400} for an
   * unparseable envelope, else {@code 202} after scheduling a claim of one
   * task from the envelope's queue.
   */
  public int handle(String timestampHeader, String signatureHeader, byte[] rawBody) {
    if (!PushSignature.verify(secret, timestampHeader, signatureHeader, rawBody, null, toleranceSecs)) {
      return 401;
    }
    PushEnvelope env;
    try {
      env = PushEnvelope.parse(rawBody);
    } catch (Orch8Exception e) {
      return 400;
    }
    if (!worker.handlerNames().contains(env.handlerName())) {
      LOG.log(Level.WARNING, () -> "orch8 push for unregistered handler " + env.handlerName() + " ignored");
      return 202;
    }
    worker.pollOnceAsync(env.handlerName(), env.queueName(), 1);
    return 202;
  }
}
