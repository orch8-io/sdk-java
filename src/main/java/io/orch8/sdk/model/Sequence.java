package io.orch8.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A stored sequence (workflow) definition. {@code blocks} is kept as raw JSON. */
public class Sequence extends ApiObject {
  @JsonProperty("id") private String id;
  @JsonProperty("tenant_id") private String tenantId;
  @JsonProperty("namespace") private String namespace;
  @JsonProperty("name") private String name;
  @JsonProperty("version") private Integer version;
  @JsonProperty("deprecated") private Boolean deprecated;
  @JsonProperty("blocks") private JsonNode blocks;
  @JsonProperty("created_at") private OffsetDateTime createdAt;

  protected Sequence() {}

  public String id() { return id; }
  public String tenantId() { return tenantId; }
  public String namespace() { return namespace; }
  public String name() { return name; }
  /** May be {@code null}. */
  public Integer version() { return version; }
  /** May be {@code null}. */
  public Boolean deprecated() { return deprecated; }
  public JsonNode blocks() { return blocks; }
  public OffsetDateTime createdAt() { return createdAt; }
}
