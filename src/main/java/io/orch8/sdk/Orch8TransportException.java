package io.orch8.sdk;

/** The request never produced an HTTP response (connection refused, reset, timeout, ...). */
public class Orch8TransportException extends Orch8Exception {
  public Orch8TransportException(String message, Throwable cause) {
    super(message, cause);
  }

  public ErrorKind kind() {
    return ErrorKind.TRANSPORT;
  }
}
