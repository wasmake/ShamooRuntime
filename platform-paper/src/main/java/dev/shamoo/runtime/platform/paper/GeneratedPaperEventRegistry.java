package dev.shamoo.runtime.platform.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.event.Event;

/** Validated generated event-name to live Paper event-class registry. */
public final class GeneratedPaperEventRegistry {
    private static final String RESOURCE = "dev/shamoo/runtime/generated/paper/model.json";
    private final Map<String, Class<? extends Event>> events;

    private GeneratedPaperEventRegistry(Map<String, Class<? extends Event>> events) {
        this.events = Map.copyOf(events);
    }

    public static GeneratedPaperEventRegistry load(ClassLoader loader) throws IOException {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("generated Paper model is missing: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(input);
            if (root.required("schemaVersion").intValue() != 2
                    || !"paper".equals(root.required("platform").textValue())) {
                throw new IOException("generated Paper model has an incompatible schema or platform");
            }
            Map<String, Class<? extends Event>> result = new HashMap<>();
            for (JsonNode event : root.required("events")) {
                String type = event.required("type").textValue();
                String javaName = event.required("javaName").textValue();
                try {
                    result.put(type, Class.forName(javaName, false, loader).asSubclass(Event.class));
                } catch (ClassNotFoundException | ClassCastException exception) {
                    throw new IOException("generated Paper event cannot be linked: " + javaName, exception);
                }
            }
            return new GeneratedPaperEventRegistry(result);
        }
    }

    public Class<? extends Event> require(String generatedType) {
        Class<? extends Event> event = events.get(generatedType);
        if (event == null) {
            throw new IllegalArgumentException("unknown generated Paper event type: " + generatedType);
        }
        return event;
    }

    public int size() {
        return events.size();
    }
}
