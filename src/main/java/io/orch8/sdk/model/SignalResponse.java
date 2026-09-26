package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code POST /instances/{id}/signals}. */
public class SignalResponse extends ApiObject {
  @JsonProperty("signal_id") private String signalId;

  protected SignalResponse() {}

  public String signalId() { return signalId; }
}
