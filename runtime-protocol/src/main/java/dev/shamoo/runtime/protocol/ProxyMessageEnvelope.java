package dev.shamoo.runtime.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Canonical versioned request/response envelope carried unchanged by plugin messaging. */
public record ProxyMessageEnvelope(
        int version, ProxyMessageType type, UUID requestId, String contractId, String contractVersion,
        String operation, byte[] payload, ProxyMessageError error) {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_IDENTIFIER_BYTES = 128;
    public static final int MAX_VERSION_BYTES = 64;
    public static final int MAX_PAYLOAD_BYTES = 30_000;
    public static final int MAX_ERROR_MESSAGE_BYTES = 1_024;
    private static final String CONTRACT_PATTERN = "[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*";
    private static final String IDENTIFIER_PATTERN = "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*";

    public ProxyMessageEnvelope {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported proxy message version: " + version);
        }
        type = Objects.requireNonNull(type, "type");
        requestId = Objects.requireNonNull(requestId, "requestId");
        if (type == ProxyMessageType.REQUEST) {
            validateText(contractId, CONTRACT_PATTERN, MAX_IDENTIFIER_BYTES, "contract id");
            validateText(contractVersion, SemanticVersion.PATTERN, MAX_VERSION_BYTES, "contract version");
            validateText(operation, IDENTIFIER_PATTERN, MAX_IDENTIFIER_BYTES, "operation");
            if (error != null) {
                throw new IllegalArgumentException("request cannot contain an error");
            }
        } else if (contractId != null || contractVersion != null || operation != null) {
            throw new IllegalArgumentException("response cannot contain request routing fields");
        }
        if (type == ProxyMessageType.ERROR) {
            error = Objects.requireNonNull(error, "error");
            payload = requirePayload(payload);
            if (payload.length != 0) {
                throw new IllegalArgumentException("error response cannot contain a payload");
            }
        } else {
            if (error != null) {
                throw new IllegalArgumentException("non-error message cannot contain an error");
            }
            payload = requirePayload(payload);
        }
    }

    public static ProxyMessageEnvelope request(UUID requestId, String contractId, String contractVersion,
            String operation, byte[] payload) {
        return new ProxyMessageEnvelope(CURRENT_VERSION, ProxyMessageType.REQUEST, requestId, contractId,
                contractVersion, operation, payload, null);
    }

    public static ProxyMessageEnvelope success(UUID requestId, byte[] payload) {
        return new ProxyMessageEnvelope(CURRENT_VERSION, ProxyMessageType.RESPONSE, requestId, null, null, null,
                payload, null);
    }

    public static ProxyMessageEnvelope error(UUID requestId, String code, String message) {
        return new ProxyMessageEnvelope(CURRENT_VERSION, ProxyMessageType.ERROR, requestId, null, null, null,
                new byte[0], new ProxyMessageError(code, message));
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    static byte[] strictUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("text is not valid Unicode", exception);
        }
    }

    static void validateText(String value, String pattern, int maximumBytes, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches(pattern) || strictUtf8(value).length > maximumBytes) {
            throw new IllegalArgumentException("invalid proxy message " + field);
        }
    }

    private static byte[] requirePayload(byte[] value) {
        byte[] copy = Objects.requireNonNull(value, "payload").clone();
        if (copy.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("proxy message payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return copy;
    }
}
