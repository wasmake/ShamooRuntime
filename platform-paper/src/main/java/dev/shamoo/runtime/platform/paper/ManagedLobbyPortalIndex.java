package dev.shamoo.runtime.platform.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable world/chunk index for deterministic lobby portal entry checks. */
public final class ManagedLobbyPortalIndex {
    private static final long MAX_INDEXED_CHUNKS = 4_096L;
    private static final long MAX_TOTAL_INDEX_ENTRIES = 16_384L;
    private static final Comparator<Portal> ORDER = Comparator.comparingInt(Portal::priority).reversed()
            .thenComparing(Portal::id);
    private final Map<String, Map<Long, List<Portal>>> worlds;

    public ManagedLobbyPortalIndex(List<Portal> portals) {
        Objects.requireNonNull(portals, "portals");
        Map<String, Map<Long, Set<Portal>>> building = new HashMap<>();
        long totalEntries = 0;
        for (Portal portal : portals) {
            if (!portal.enabled()) {
                continue;
            }
            int minimumChunkX = floorBlock(portal.minimumX()) >> 4;
            int maximumChunkX = floorBlock(portal.maximumX()) >> 4;
            int minimumChunkZ = floorBlock(portal.minimumZ()) >> 4;
            int maximumChunkZ = floorBlock(portal.maximumZ()) >> 4;
            long chunks = (long) maximumChunkX - minimumChunkX + 1L;
            chunks *= (long) maximumChunkZ - minimumChunkZ + 1L;
            if (chunks > MAX_INDEXED_CHUNKS) {
                throw new IllegalArgumentException("portal " + portal.id() + " spans more than 4096 chunks");
            }
            totalEntries += chunks;
            if (totalEntries > MAX_TOTAL_INDEX_ENTRIES) {
                throw new IllegalArgumentException("enabled portals exceed 16384 aggregate chunk index entries");
            }
            Map<Long, Set<Portal>> index = building.computeIfAbsent(
                    portal.world(), ignored -> new HashMap<>());
            for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                    index.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new LinkedHashSet<>()).add(portal);
                }
            }
        }
        Map<String, Map<Long, List<Portal>>> immutable = new HashMap<>();
        building.forEach((world, chunks) -> {
            Map<Long, List<Portal>> values = new HashMap<>();
            chunks.forEach((key, portalsAtChunk) -> values.put(key,
                    portalsAtChunk.stream().sorted(ORDER).toList()));
            immutable.put(world, Map.copyOf(values));
        });
        worlds = Map.copyOf(immutable);
    }

    public Portal highest(String world, double x, double y, double z) {
        Map<Long, List<Portal>> chunks = worlds.get(world);
        if (chunks == null) {
            return null;
        }
        List<Portal> candidates = chunks.get(chunkKey(floorBlock(x) >> 4, floorBlock(z) >> 4));
        if (candidates == null) {
            return null;
        }
        for (Portal portal : candidates) {
            if (portal.contains(x, y, z)) {
                return portal;
            }
        }
        return null;
    }

    public List<Portal> portals() {
        List<Portal> result = new ArrayList<>();
        worlds.values().forEach(chunks -> chunks.values().forEach(result::addAll));
        return result.stream().distinct().sorted(ORDER).toList();
    }

    static long chunkKey(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }

    private static int floorBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    public record Portal(String id, String world,
            double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ,
            boolean enabled, String permission, int priority, long cooldownMillis, String destination,
            ManagedLobbyConfig.Action action, boolean visualize) {
        public Portal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(action, "action");
            if (!Double.isFinite(minimumX) || !Double.isFinite(minimumY) || !Double.isFinite(minimumZ)
                    || !Double.isFinite(maximumX) || !Double.isFinite(maximumY) || !Double.isFinite(maximumZ)
                    || minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
                throw new IllegalArgumentException("portal bounds must be finite ordered coordinates");
            }
            if (!integral(minimumX) || !integral(minimumY) || !integral(minimumZ)
                    || !integral(maximumX) || !integral(maximumY) || !integral(maximumZ)) {
                throw new IllegalArgumentException("portal bounds must use integer block coordinates");
            }
        }

        public boolean contains(double x, double y, double z) {
            return floorBlock(x) >= minimumX && floorBlock(x) <= maximumX
                    && floorBlock(y) >= minimumY && floorBlock(y) <= maximumY
                    && floorBlock(z) >= minimumZ && floorBlock(z) <= maximumZ;
        }

        private static boolean integral(double value) {
            return value == Math.rint(value);
        }
    }
}
