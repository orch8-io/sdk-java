package io.orch8.sdk.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PushSignatureTest {
  @Test
  void sharedVectors() throws Exception {
    JsonNode cases;
    try (InputStream in = getClass().getResourceAsStream("/push_signatures.json")) {
      cases = new ObjectMapper().readTree(in).get("cases");
    }
    List<String> wrong = new ArrayList<>();
    for (JsonNode c : cases) {
      boolean got = PushSignature.verify(
          c.get("secret").asText(),
          c.get("timestamp").isNull() ? null : c.get("timestamp").asText(),
          c.get("signature").isNull() ? null : c.get("signature").asText(),
          c.get("body").asText().getBytes(StandardCharsets.UTF_8),
          c.get("now").asLong(),
          c.get("tolerance_secs").asLong());
      if (got != c.get("valid").asBoolean()) {
        wrong.add(c.get("name").asText());
      }
    }
    assertTrue(cases.size() >= 10);
    assertEquals(List.of(), wrong);
  }

  @Test
  void signRoundTripAndThrowingVariant() {
    byte[] body = "{\"handler_name\":\"echo\"}".getBytes(StandardCharsets.UTF_8);
    String now = String.valueOf(System.currentTimeMillis() / 1000);
    String sig = PushSignature.sign("s3cret", now, body);
    assertTrue(sig.startsWith("sha256=") && sig.length() == 71);
    assertTrue(PushSignature.verify("s3cret", now, sig, body));
    PushSignature.verifyOrThrow("s3cret", now, sig, body, null, 300);
    assertThrows(PushSignatureException.class,
        () -> PushSignature.verifyOrThrow("other", now, sig, body, null, 300));
  }

  @Test
  void emptySecretIsAConfigurationError() {
    assertThrows(IllegalArgumentException.class, () -> PushSignature.verify("", "1", "sha256=00", new byte[0]));
  }
}
