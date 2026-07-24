package dev.shamoo.runtime.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Strict binary codec for the stable plugin-message envelope; payload encoding is caller-defined. */
public final class ProxyMessageCodec {
    public static final int MAX_ENVELOPE_BYTES = 32_766;
    private static final int MAGIC = 0x53484d50;

    @SuppressWarnings("PMD.ExhaustiveSwitchHasDefault")
    public byte[] encode(ProxyMessageEnvelope envelope) {
        byte[] payload = envelope.payload();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(envelope.version());
            output.writeByte(roleCode(envelope.type()));
            output.writeLong(envelope.requestId().getMostSignificantBits());
            output.writeLong(envelope.requestId().getLeastSignificantBits());
            switch (envelope.type()) {
                case REQUEST -> {
                    byte[] contractId = ProxyMessageEnvelope.strictUtf8(envelope.contractId());
                    byte[] contractVersion = ProxyMessageEnvelope.strictUtf8(envelope.contractVersion());
                    byte[] operation = ProxyMessageEnvelope.strictUtf8(envelope.operation());
                    output.writeShort(contractId.length);
                    output.writeShort(contractVersion.length);
                    output.writeShort(operation.length);
                    output.writeInt(payload.length);
                    output.write(contractId);
                    output.write(contractVersion);
                    output.write(operation);
                    output.write(payload);
                }
                case RESPONSE -> {
                    output.writeInt(payload.length);
                    output.write(payload);
                }
                case ERROR -> {
                    byte[] code = ProxyMessageEnvelope.strictUtf8(envelope.error().code());
                    byte[] message = ProxyMessageEnvelope.strictUtf8(envelope.error().message());
                    output.writeShort(code.length);
                    output.writeShort(message.length);
                    output.write(code);
                    output.write(message);
                }
                default -> throw new IllegalArgumentException("invalid proxy message type");
            }
            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENVELOPE_BYTES) {
                throw new IllegalArgumentException("proxy message envelope exceeds " + MAX_ENVELOPE_BYTES + " bytes");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to encode in-memory envelope", exception);
        }
    }

    public ProxyMessageEnvelope decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("invalid proxy message envelope size");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("invalid proxy message magic");
            }
            int version = input.readUnsignedByte();
            int type = input.readUnsignedByte();
            UUID requestId = new UUID(input.readLong(), input.readLong());
            ProxyMessageType role = decodeRole(type);
            ProxyMessageEnvelope envelope = switch (role) {
                case REQUEST -> decodeRequest(input, version, requestId);
                case RESPONSE -> new ProxyMessageEnvelope(version, role, requestId, null, null, null,
                        readPayload(input), null);
                case ERROR -> decodeError(input, version, requestId);
            };
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing proxy message bytes");
            }
            return envelope;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("truncated proxy message envelope", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid proxy message envelope", exception);
        }
    }

    private static ProxyMessageEnvelope decodeRequest(DataInputStream input, int version, UUID requestId)
            throws IOException {
        int contractLength = input.readUnsignedShort();
        int versionLength = input.readUnsignedShort();
        int operationLength = input.readUnsignedShort();
        int payloadLength = input.readInt();
        validateLength(contractLength, ProxyMessageEnvelope.MAX_IDENTIFIER_BYTES, "contract id");
        validateLength(versionLength, ProxyMessageEnvelope.MAX_VERSION_BYTES, "contract version");
        validateLength(operationLength, ProxyMessageEnvelope.MAX_IDENTIFIER_BYTES, "operation");
        validatePayloadLength(payloadLength);
        if (input.available() != contractLength + versionLength + operationLength + payloadLength) {
            throw new IllegalArgumentException("invalid proxy request field lengths");
        }
        return new ProxyMessageEnvelope(version, ProxyMessageType.REQUEST, requestId,
                readUtf8(input, contractLength), readUtf8(input, versionLength), readUtf8(input, operationLength),
                input.readNBytes(payloadLength), null);
    }

    private static ProxyMessageEnvelope decodeError(DataInputStream input, int version, UUID requestId)
            throws IOException {
        int codeLength = input.readUnsignedShort();
        int messageLength = input.readUnsignedShort();
        validateLength(codeLength, ProxyMessageEnvelope.MAX_IDENTIFIER_BYTES, "error code");
        validateLength(messageLength, ProxyMessageEnvelope.MAX_ERROR_MESSAGE_BYTES, "error message");
        if (input.available() != codeLength + messageLength) {
            throw new IllegalArgumentException("invalid proxy error field lengths");
        }
        return new ProxyMessageEnvelope(version, ProxyMessageType.ERROR, requestId, null, null, null, new byte[0],
                new ProxyMessageError(readUtf8(input, codeLength), readUtf8(input, messageLength)));
    }

    private static byte[] readPayload(DataInputStream input) throws IOException {
        int payloadLength = input.readInt();
        validatePayloadLength(payloadLength);
        if (input.available() != payloadLength) {
            throw new IllegalArgumentException("invalid proxy response payload length");
        }
        return input.readNBytes(payloadLength);
    }

    private static String readUtf8(DataInputStream input, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid UTF-8 in proxy message", exception);
        }
    }

    private static void validateLength(int length, int maximum, String field) {
        if (length == 0 || length > maximum) {
            throw new IllegalArgumentException("invalid proxy message " + field + " length");
        }
    }

    private static void validatePayloadLength(int length) {
        if (length < 0 || length > ProxyMessageEnvelope.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("invalid proxy message payload length");
        }
    }

    private static int roleCode(ProxyMessageType role) {
        return switch (role) {
            case REQUEST -> 0;
            case RESPONSE -> 1;
            case ERROR -> 2;
        };
    }

    private static ProxyMessageType decodeRole(int role) {
        return switch (role) {
            case 0 -> ProxyMessageType.REQUEST;
            case 1 -> ProxyMessageType.RESPONSE;
            case 2 -> ProxyMessageType.ERROR;
            default -> throw new IllegalArgumentException("invalid proxy message type");
        };
    }
}
