package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Response of {@code POST /sequences}: {@code {id, warnings?}}. */
public class CreateSequenceResponse extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("warnings") private JsonNode warnings;

  protected CreateSequenceResponse() {}

  public String id() { return id; }
  /** Validation warnings, or {@code null} when none were returned. */
  public JsonNode warnings() { return warnings; }
}
