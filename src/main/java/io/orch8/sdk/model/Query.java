package io.orch8.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query-string builder for list endpoints. {@code null} values are skipped.
 *
 * <pre>{@code client.instances().list(Query.create().namespace("default").state("running").limit(50));}</pre>
 */
public final class Query {
  private final Map<String, Object> params = new LinkedHashMap<>();

  private Query() {}

  public static Query create() {
    return new Query();
  }

  public static Query of(Map<String, ?> params) {
    Query q = new Query();
    if (params != null) {
      params.forEach(q::set);
    }
    return q;
  }

  public Query set(String name, Object value) {
    if (value == null) {
      params.remove(name);
    } else {
      params.put(name, value);
    }
    return this;
  }

  public Query tenantId(String v) { return set("tenant_id", v); }
  public Query namespace(String v) { return set("namespace", v); }
  public Query sequenceId(String v) { return set("sequence_id", v); }
  public Query state(String v) { return set("state", v); }
  public Query status(String v) { return set("status", v); }
  public Query queue(String v) { return set("queue", v); }
  public Query handler(String v) { return set("handler", v); }
  public Query limit(Integer v) { return set("limit", v); }
  public Query offset(Integer v) { return set("offset", v); }

  public Map<String, Object> toMap() {
    return Collections.unmodifiableMap(params);
  }
}
