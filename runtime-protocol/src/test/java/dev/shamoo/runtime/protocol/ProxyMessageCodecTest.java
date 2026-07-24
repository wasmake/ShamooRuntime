package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class ProxyMessageCodecTest {
    private static final UUID REQUEST_ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private final ProxyMessageCodec codec = new ProxyMessageCodec();

    @Test
    void matchesCrossLanguageGoldenFramesAndDecodesEveryRole() throws IOException {
        Map<String, byte[]> golden = goldenFrames();
        ProxyMessageEnvelope request = ProxyMessageEnvelope.request(REQUEST_ID, "example/routing", "1.2.3",
                "lookup", new byte[] {0, 1, (byte) 0xfe, (byte) 0xff});
        ProxyMessageEnvelope success = ProxyMessageEnvelope.success(REQUEST_ID,
                new byte[] {0, 1, (byte) 0xfe, (byte) 0xff});
        ProxyMessageEnvelope error = ProxyMessageEnvelope.error(REQUEST_ID, "unavailable", "Provider is reloading.");

        assertArrayEquals(golden.get("request"), codec.encode(request));
        assertArrayEquals(golden.get("success"), codec.encode(success));
        assertArrayEquals(golden.get("error"), codec.encode(error));
        assertEnvelope(request, codec.decode(golden.get("request")));
        assertEnvelope(success, codec.decode(golden.get("success")));
        assertEnvelope(error, codec.decode(golden.get("error")));
    }

    @Test
    void rejectsOversizeMalformedUtf8TruncatedTrailingAndInvalidRoles() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> ProxyMessageEnvelope.request(REQUEST_ID, "contract",
                "1.0.0", "operation", new byte[ProxyMessageEnvelope.MAX_PAYLOAD_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> ProxyMessageEnvelope.request(REQUEST_ID, "bad route",
                "1.0.0", "operation", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ProxyMessageEnvelope.request(REQUEST_ID, "contract",
                "v1.0.0", "operation", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyMessageEnvelope.error(REQUEST_ID, "failed", "\ud800"));
        byte[] valid = goldenFrames().get("request");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length + 1)));
        byte[] badUtf8 = valid.clone();
        badUtf8[32] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badUtf8));
        byte[] badRole = valid.clone();
        badRole[5] = 3;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badRole));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[ProxyMessageCodec.MAX_ENVELOPE_BYTES + 1]));
    }

    @Test
    void copiesMutablePayloadsOnConstructionEncodingAndAccess() {
        byte[] source = new byte[] {1, 2};
        ProxyMessageEnvelope envelope = ProxyMessageEnvelope.success(REQUEST_ID, source);
        source[0] = 9;
        byte[] first = envelope.payload();
        first[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, envelope.payload());

        byte[] encoded = codec.encode(envelope);
        ProxyMessageEnvelope decoded = codec.decode(encoded);
        encoded[encoded.length - 1] = 9;
        assertArrayEquals(new byte[] {1, 2}, decoded.payload());
    }

    private static Map<String, byte[]> goldenFrames() throws IOException {
        String fixture = new String(ProxyMessageCodecTest.class.getResourceAsStream(
                "/communication-v1-golden.hex").readAllBytes(), StandardCharsets.US_ASCII);
        return fixture.lines().filter(line -> !line.isBlank()).map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> HexFormat.of().parseHex(parts[1])));
    }

    private static void assertEnvelope(ProxyMessageEnvelope expected, ProxyMessageEnvelope actual) {
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.contractId(), actual.contractId());
        assertEquals(expected.contractVersion(), actual.contractVersion());
        assertEquals(expected.operation(), actual.operation());
        assertArrayEquals(expected.payload(), actual.payload());
        assertEquals(expected.error(), actual.error());
    }
}
