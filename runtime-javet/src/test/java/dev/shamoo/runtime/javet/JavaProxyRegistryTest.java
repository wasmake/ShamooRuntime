package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts"})
class JavaProxyRegistryTest {
    private static final String BOUNDARY = "test boundary";

    @Test
    void preservesNullDataAcrossTheJavetBoundary() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("target", null);
        source.put("values", new ArrayList<>(java.util.Arrays.asList("value", null)));

        Map<?, ?> safe = assertInstanceOf(Map.class,
                JavaProxyRegistry.requireDataValue(source, BOUNDARY));
        List<?> values = assertInstanceOf(List.class, safe.get("values"));

        assertNull(safe.get("target"));
        assertEquals("value", values.getFirst());
        assertNull(values.get(1));
    }

    @Test
    void acceptsSharedContainersAcrossTheJavetBoundary() {
        List<Object> sharedList = List.of("value");
        Map<String, Object> sharedMap = Map.of("values", sharedList);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("firstMap", sharedMap);
        source.put("secondMap", sharedMap);
        source.put("firstList", sharedList);
        source.put("secondList", sharedList);

        Map<?, ?> safe = assertInstanceOf(Map.class,
                JavaProxyRegistry.requireDataValue(source, BOUNDARY));

        assertEquals(safe.get("firstMap"), safe.get("secondMap"));
        assertEquals(safe.get("firstList"), safe.get("secondList"));
    }

    @Test
    void rejectsActualCyclesAcrossTheJavetBoundary() {
        Map<String, Object> cyclicMap = new LinkedHashMap<>();
        cyclicMap.put("self", cyclicMap);
        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);

        assertThrows(IllegalArgumentException.class,
                () -> JavaProxyRegistry.requireDataValue(cyclicMap, BOUNDARY));
        assertThrows(IllegalArgumentException.class,
                () -> JavaProxyRegistry.requireDataValue(cyclicList, BOUNDARY));
    }
}
