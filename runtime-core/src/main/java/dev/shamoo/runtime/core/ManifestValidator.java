package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.CompatibilityNegotiator;
import dev.shamoo.runtime.protocol.CompatibilityResult;
import dev.shamoo.runtime.protocol.ManifestCodec;
import dev.shamoo.runtime.protocol.PluginDescriptor;
import java.util.Objects;

/** Runtime-core admission boundary for parsing and compatibility validation. */
public final class ManifestValidator {
    private final ManifestCodec codec = new ManifestCodec();
    private final CompatibilityNegotiator negotiator = new CompatibilityNegotiator();

    public PluginDescriptor parseCompatible(String json, CompatibilityInput runtime) {
        PluginDescriptor descriptor = codec.parse(json);
        CompatibilityResult result = negotiator.negotiate(descriptor, Objects.requireNonNull(runtime, "runtime"));
        if (!result.compatible()) {
            throw new IncompatibleManifestException(descriptor.name(), result);
        }
        return descriptor;
    }
}
