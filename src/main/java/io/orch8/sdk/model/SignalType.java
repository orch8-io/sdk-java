package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * Signal kind: one of the built-ins ({@link #PAUSE}, {@link #RESUME},
 * {@link #CANCEL}, {@link #UPDATE_CONTEXT}) serialized as a string, or
 * {@link #custom(String)} serialized as {@code {"custom": "<name>"}}.
 */
public final class SignalType {
  public static final SignalType PAUSE = new SignalType("pause", false);
  public static final SignalType RESUME = new SignalType("resume", false);
  public static final SignalType CANCEL = new SignalType("cancel", false);
  public static final SignalType UPDATE_CONTEXT = new SignalType("update_context", false);

  private final String name;
  private final boolean custom;

  private SignalType(String name, boolean custom) {
    this.name = Objects.requireNonNull(name, "name");
    this.custom = custom;
  }

  public static SignalType custom(String name) {
    return new SignalType(name, true);
  }

  /** A built-in signal by wire name ({@code pause}, {@code resume}, ...). */
  public static SignalType builtIn(String name) {
    return new SignalType(name, false);
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  static SignalType fromJson(JsonNode node) {
    if (node.isTextual()) {
      return builtIn(node.asText());
    }
    if (node.isObject() && node.hasNonNull("custom")) {
      return custom(node.get("custom").asText());
    }
    throw new IllegalArgumentException("invalid signal_type: " + node);
  }

  public String name() { return name; }

  public boolean isCustom() { return custom; }

  @JsonValue
  Object toJson() {
    return custom ? Map.of("custom", name) : name;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SignalType && ((SignalType) o).name.equals(name) && ((SignalType) o).custom == custom;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, custom);
  }

  @Override
  public String toString() {
    return custom ? "custom:" + name : name;
  }
}
