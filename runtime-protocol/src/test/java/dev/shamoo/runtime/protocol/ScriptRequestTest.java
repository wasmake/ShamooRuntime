package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptRequestTest {
    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("platform", "paper");

        ScriptRequest request = new ScriptRequest("request-1", "1 + 1", attributes);
        attributes.clear();

        assertThrows(UnsupportedOperationException.class, () -> request.attributes().clear(),
            "request attributes must be immutable");
    }
}
