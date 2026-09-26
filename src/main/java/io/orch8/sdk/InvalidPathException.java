package io.orch8.sdk;

/** A request path was rejected before sending (e.g. protocol-relative {@code //host/...}). */
public class InvalidPathException extends Orch8Exception {
  public InvalidPathException(String message) {
    super(message);
  }
}
