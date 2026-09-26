package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for API objects. Fields the SDK does not model are kept in
 * {@link #extra()} (the engine adds fields over time) and are written back
 * when the object is serialized.
 */
public abstract class ApiObject {
  @JsonIgnore
  private final Map<String, JsonNode> extra = new LinkedHashMap<>();

  /** Unmodelled fields as raw JSON, keyed by wire name. Never {@code null}. */
  @JsonAnyGetter
  public Map<String, JsonNode> extra() {
    return Collections.unmodifiableMap(extra);
  }

  /** Raw value of an unmodelled field, or {@code null}. */
  public JsonNode extra(String name) {
    return extra.get(name);
  }

  @JsonAnySetter
  protected void putExtra(String name, JsonNode value) {
    extra.put(name, value);
  }
}
