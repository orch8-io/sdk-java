package io.orch8.sdk.push;

import io.orch8.sdk.Orch8Exception;

/** A push-dispatch request failed signature verification. */
public class PushSignatureException extends Orch8Exception {
  public PushSignatureException(String message) {
    super(message);
  }
}
