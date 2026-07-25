package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.core.CompiledBindingMetadata;
import dev.shamoo.runtime.protocol.ManifestCodec;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.PluginDescriptor;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.AvoidDuplicateLiterals"})
class ShamooPluginMetadataTest {
    @Test
    void derivesNmsAndPacketAuthorizationOnlyFromSelectedPaperTarget() {
        PluginDescriptor descriptor = new ManifestCodec().parse("""
                {"name":"authorization","displayName":"Authorization","version":"1.0.0",
                "shamoo":{"api":"*","runtime":"*","manifest":2},
                "platforms":{"paper":{"enabled":true,"minecraft":"*","paperApi":"*",
                "nms":true,"packets":true},"velocity":{"enabled":true,"velocityApi":"*"}},
                "dependencies":{"required":{},"optional":{},"loadBefore":[],"loadAfter":[]},
                "node":{"builtins":[],"filesystem":{"read":[],"write":[]},"network":false,
                "workers":false,"childProcess":false,"nativeAddons":false},
                "reload":{"watch":false,"debounceMs":0,"preserveState":false},
                "compiler":{"version":"test","components":[
                {"id":"paper-listener","kind":"event-listener","name":"PaperListener",
                "file":"src/paper.ts","platform":"paper","decorators":[],"constructor":[],
                "properties":[],"methods":[
                {"name":"onEvent","invocation":"event","decorators":[],"parameters":[],
                "location":{"file":"src/paper.ts","line":1,"column":1}},
                {"name":"onPacket","invocation":"packet","decorators":[],"parameters":[],
                "location":{"file":"src/paper.ts","line":2,"column":1}},
                {"name":"invokeNms","decorators":[],"parameters":[],
                "location":{"file":"src/paper.ts","line":3,"column":1}}],
                "location":{"file":"src/paper.ts","line":1,"column":1}},
                {"id":"velocity-listener","kind":"event-listener","name":"VelocityListener",
                "file":"src/velocity.ts","platform":"velocity","decorators":[],"constructor":[],
                "properties":[],"methods":[
                {"name":"onEvent","invocation":"event","decorators":[],"parameters":[],
                "location":{"file":"src/velocity.ts","line":1,"column":1}}],
                "location":{"file":"src/velocity.ts","line":1,"column":1}}],"modules":[],
                "communication":{"services":[],"events":[],"consumers":[]}}}
                """);

        ShamooPluginMetadata paper = ShamooPluginMetadata.from(descriptor, PlatformKind.PAPER);
        ShamooPluginMetadata velocity = ShamooPluginMetadata.from(descriptor, PlatformKind.VELOCITY);

        assertTrue(paper.permitsPlatformOperation("paperNmsInvoke",
                binding("paperNmsInvoke", "paper-listener", "invokeNms")));
        assertTrue(paper.permitsPlatformOperation("paperSubscribePacket",
                binding("paperSubscribePacket", "paper-listener", "onPacket")));
        assertTrue(paper.permitsPlatformOperation("paperSubscribeEvent",
                binding("paperSubscribeEvent", "paper-listener", "onEvent")));
        assertFalse(paper.permitsPlatformOperation("paperSubscribeEvent",
                binding("paperSubscribeEvent", "paper-listener", "onPacket")));
        assertFalse(velocity.permitsPlatformOperation("paperNmsInvoke",
                binding("paperNmsInvoke", "paper-listener", "invokeNms")));
        assertFalse(velocity.permitsPlatformOperation("paperSubscribePacket",
                binding("paperSubscribePacket", "paper-listener", "onPacket")));
        assertEquals(Set.of("version", "components", "modules", "communication"), paper.data().keySet());
    }

    private static CompiledBindingMetadata binding(String operation, String componentId, String method) {
        return new CompiledBindingMetadata("paper", operation, componentId, method, ProtocolVersion.CURRENT);
    }
}
