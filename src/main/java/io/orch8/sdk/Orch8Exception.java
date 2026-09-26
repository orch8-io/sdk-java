package io.orch8.sdk;

/**
 * Root of every exception thrown by the Orch8 SDK. Unchecked, so Kotlin and
 * lambda-heavy Java callers never need {@code throws} clauses.
 */
public class Orch8Exception extends RuntimeException {
  public Orch8Exception(String message) {
    super(message);
  }

  public Orch8Exception(String message, Throwable cause) {
    super(message, cause);
  }
}
