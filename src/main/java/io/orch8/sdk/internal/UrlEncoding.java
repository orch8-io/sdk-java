package io.orch8.sdk.internal;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/** RFC 3986 percent-encoding helpers. */
public final class UrlEncoding {
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private UrlEncoding() {}

  /** Encodes {@code value} as exactly one path segment: {@code a/b c} becomes {@code a%2Fb%20c}. */
  public static String segment(String value) {
    if (value == null) {
      throw new IllegalArgumentException("path id must not be null");
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '.' || c == '_' || c == '~') {
        sb.append((char) c);
      } else {
        sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
      }
    }
    return sb.toString();
  }

  /** Builds {@code ?k=v&...} from non-null entries, or an empty string. */
  public static String query(Map<String, ?> query) {
    if (query == null || query.isEmpty()) {
      return "";
    }
    StringJoiner joiner = new StringJoiner("&", "?", "");
    joiner.setEmptyValue("");
    for (Map.Entry<String, ?> e : query.entrySet()) {
      if (e.getKey() == null || e.getValue() == null) {
        continue;
      }
      joiner.add(segment(e.getKey()) + "=" + segment(String.valueOf(e.getValue())));
    }
    return joiner.toString();
  }
}
