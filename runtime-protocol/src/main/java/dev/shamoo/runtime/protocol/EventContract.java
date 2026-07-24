package dev.shamoo.runtime.protocol;

import java.util.Objects;

/** Stable cross-plugin event identity with an exact semantic payload version. */
public record EventContract(String name, SemanticVersion version) {
    public EventContract {
        name = ServiceContract.contractName(name);
        version = Objects.requireNonNull(version, "version");
    }
}
