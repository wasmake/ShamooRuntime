package dev.shamoo.runtime.protocol;

/** Bounded, non-sensitive remote error returned by the communication protocol. */
public record ProxyMessageError(String code, String message) {
    public ProxyMessageError {
        ProxyMessageEnvelope.validateText(code, "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*",
                ProxyMessageEnvelope.MAX_IDENTIFIER_BYTES, "error code");
        if (message == null || message.isBlank()
                || ProxyMessageEnvelope.strictUtf8(message).length > ProxyMessageEnvelope.MAX_ERROR_MESSAGE_BYTES) {
            throw new IllegalArgumentException("invalid proxy message error message");
        }
    }
}
