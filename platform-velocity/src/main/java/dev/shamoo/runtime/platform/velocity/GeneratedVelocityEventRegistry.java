package dev.shamoo.runtime.platform.velocity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Validated generated event-name to live Velocity event-class registry. */
public final class GeneratedVelocityEventRegistry {
    private static final String RESOURCE = "dev/shamoo/runtime/generated/velocity/model.json";
    private final Map<String, Class<?>> events;

    private GeneratedVelocityEventRegistry(Map<String, Class<?>> events) {
        this.events = Map.copyOf(events);
    }

    public static GeneratedVelocityEventRegistry load(ClassLoader loader) throws IOException {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("generated Velocity model is missing: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(input);
            if (root.required("schemaVersion").intValue() != 2
                    || !"velocity".equals(root.required("platform").textValue())) {
                throw new IOException("generated Velocity model has an incompatible schema or platform");
            }
            Map<String, Class<?>> result = new HashMap<>();
            for (JsonNode event : root.required("events")) {
                String type = event.required("type").textValue();
                String javaName = event.required("javaName").textValue();
                try {
                    result.put(type, Class.forName(javaName, false, loader));
                } catch (ClassNotFoundException exception) {
                    throw new IOException("generated Velocity event cannot be linked: " + javaName, exception);
                }
            }
            return new GeneratedVelocityEventRegistry(result);
        }
    }

    public Class<?> require(String generatedType) {
        Class<?> event = events.get(generatedType);
        if (event == null) {
            throw new IllegalArgumentException("unknown generated Velocity event type: " + generatedType);
        }
        return event;
    }

    public int size() {
        return events.size();
    }
}
