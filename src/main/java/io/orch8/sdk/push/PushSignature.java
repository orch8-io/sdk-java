package io.orch8.sdk.push;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies push-dispatch requests (WORKER_PROTOCOL.md §8.2):
 * {@code X-Orch8-Signature: sha256=<hex(HMAC-SHA256(secret, "<X-Orch8-Timestamp>." + rawBody))>}.
 * The MAC is computed over the exact raw body bytes and compared in constant time.
 */
public final class PushSignature {
  /** Header carrying the Unix-seconds timestamp. */
  public static final String TIMESTAMP_HEADER = "X-Orch8-Timestamp";
  /** Header carrying {@code sha256=<hex>}. */
  public static final String SIGNATURE_HEADER = "X-Orch8-Signature";
  /** Default replay window (both directions). */
  public static final long DEFAULT_TOLERANCE_SECS = 300;

  private static final String PREFIX = "sha256=";

  private PushSignature() {}

  /** Verifies against the current clock with the default 300 s tolerance. */
  public static boolean verify(String secret, String timestampHeader, String signatureHeader, byte[] rawBody) {
    return verify(secret, timestampHeader, signatureHeader, rawBody, null, DEFAULT_TOLERANCE_SECS);
  }

  /**
   * @param secret          push secret (must be non-empty; an empty secret is a configuration error)
   * @param timestampHeader value of {@code X-Orch8-Timestamp}, or {@code null} when absent
   * @param signatureHeader value of {@code X-Orch8-Signature}, or {@code null} when absent
   * @param rawBody         exact request body bytes
   * @param nowEpochSecs    clock override in Unix seconds, or {@code null} for the system clock
   * @param toleranceSecs   accepted |now - timestamp| in seconds
   * @return {@code true} only for a well-formed, fresh, matching signature
   * @throws IllegalArgumentException if {@code secret} is null or empty
   */
  public static boolean verify(String secret, String timestampHeader, String signatureHeader, byte[] rawBody,
                               Long nowEpochSecs, long toleranceSecs) {
    return check(secret, timestampHeader, signatureHeader, rawBody, nowEpochSecs, toleranceSecs) == null;
  }

  /** Throwing variant of {@link #verify(String, String, String, byte[], Long, long)}. */
  public static void verifyOrThrow(String secret, String timestampHeader, String signatureHeader, byte[] rawBody,
                                   Long nowEpochSecs, long toleranceSecs) {
    String problem = check(secret, timestampHeader, signatureHeader, rawBody, nowEpochSecs, toleranceSecs);
    if (problem != null) {
      throw new PushSignatureException(problem);
    }
  }

  /** Computes the header value {@code sha256=<hex>} for {@code timestamp} and {@code rawBody}. */
  public static String sign(String secret, String timestamp, byte[] rawBody) {
    return PREFIX + toHex(mac(secret, timestamp, rawBody));
  }

  private static String check(String secret, String timestamp, String signature, byte[] body,
                              Long now, long toleranceSecs) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalArgumentException("push secret must be configured (empty secret)");
    }
    if (timestamp == null || !timestamp.matches("-?\\d{1,19}")) {
      return "missing or non-integer " + TIMESTAMP_HEADER;
    }
    if (signature == null || !signature.startsWith(PREFIX)) {
      return "missing or malformed " + SIGNATURE_HEADER;
    }
    String hex = signature.substring(PREFIX.length());
    byte[] given = fromHex(hex);
    if (given == null || given.length != 32) {
      return "signature must be sha256= followed by 64 hex digits";
    }
    long ts;
    try {
      ts = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      return "non-integer " + TIMESTAMP_HEADER;
    }
    long current = now != null ? now : System.currentTimeMillis() / 1000;
    if (Math.abs(current - ts) > toleranceSecs) {
      return "timestamp outside the " + toleranceSecs + "s tolerance window";
    }
    byte[] expected = mac(secret, timestamp, body == null ? new byte[0] : body);
    if (!MessageDigest.isEqual(expected, given)) {
      return "signature mismatch";
    }
    return null;
  }

  private static byte[] mac(String secret, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(body);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  private static byte[] fromHex(String hex) {
    if (hex.length() % 2 != 0) {
      return null;
    }
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(hex.charAt(2 * i), 16);
      int lo = Character.digit(hex.charAt(2 * i + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
