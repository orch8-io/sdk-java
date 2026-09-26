package io.orch8.sdk.internal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.orch8.sdk.Orch8Exception;

/** Shared, preconfigured Jackson mapper. Internal; not part of the stable API. */
public final class Json {
  private static final ObjectMapper MAPPER = newMapper();

  private Json() {}

  /** A fresh mapper with the SDK's settings (snake_case fields are declared explicitly on models). */
  public static ObjectMapper newMapper() {
    ObjectMapper m = new ObjectMapper();
    m.registerModule(new JavaTimeModule());
    m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    m.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    m.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    m.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
    return m;
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new Orch8Exception("failed to serialize request body: " + e.getOriginalMessage(), e);
    }
  }

  public static JsonNode toNode(Object value) {
    return value == null ? null : MAPPER.valueToTree(value);
  }

  public static <T> T convert(JsonNode node, Class<T> type) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    try {
      return MAPPER.treeToValue(node, type);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new Orch8Exception("unexpected response shape for " + type.getSimpleName() + ": " + e.getMessage(), e);
    }
  }
}
