package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code POST /instances}: {@code {id, deduplicated?}}. */
public class CreateInstanceResponse extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("deduplicated") private Boolean deduplicated;

  protected CreateInstanceResponse() {}

  public String id() { return id; }

  /** True when an existing instance was returned for a repeated {@code idempotency_key}. */
  public boolean deduplicated() { return Boolean.TRUE.equals(deduplicated); }
}
