package dev.shamoo.runtime.protocol;

import java.util.Objects;

/** Stable cross-plugin service identity with an exact semantic contract version. */
public record ServiceContract(String name, SemanticVersion version) {
    public ServiceContract {
        name = contractName(name);
        version = Objects.requireNonNull(version, "version");
    }

    public static String contractName(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("invalid contract name: " + value);
        }
        return value;
    }
}
