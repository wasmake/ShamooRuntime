package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts"})
class JavaProxyRegistryTest {
    @Test
    void preservesNullDataAcrossTheJavetBoundary() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("target", null);
        source.put("values", new ArrayList<>(java.util.Arrays.asList("value", null)));

        Map<?, ?> safe = assertInstanceOf(Map.class,
                JavaProxyRegistry.requireDataValue(source, "test boundary"));
        List<?> values = assertInstanceOf(List.class, safe.get("values"));

        assertNull(safe.get("target"));
        assertEquals("value", values.getFirst());
        assertNull(values.get(1));
    }
}
