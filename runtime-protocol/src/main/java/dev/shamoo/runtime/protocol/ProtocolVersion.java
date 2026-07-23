package dev.shamoo.runtime.protocol;

/** The wire contract version shared by hosts and runtimes. */
public record ProtocolVersion(int major, int minor) {
    public static final ProtocolVersion CURRENT = new ProtocolVersion(1, 0);

    public ProtocolVersion {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("protocol versions must be positive");
        }
    }

    public boolean isCompatibleWith(ProtocolVersion other) {
        return other != null && major == other.major && minor >= other.minor;
    }
}
