package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.DependencyPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/** Resolves semantic dependencies and ordering hints with stable lexical tie-breaking. */
public final class PluginDependencyGraph {
    public DependencyResolution resolve(Collection<InstalledPluginCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<PluginId, InstalledPluginCandidate> installed = new java.util.TreeMap<>(
                Comparator.comparing(PluginId::value));
        for (InstalledPluginCandidate candidate : candidates) {
            InstalledPluginCandidate duplicate = installed.putIfAbsent(candidate.pluginId(), candidate);
            if (duplicate != null) {
                throw new IllegalArgumentException("duplicate plugin id " + candidate.pluginId());
            }
        }
        Map<PluginId, Set<PluginId>> dependencies = new LinkedHashMap<>();
        Map<PluginId, Set<PluginId>> requiredDependencies = new LinkedHashMap<>();
        Map<PluginId, List<DependencyBlock>> blocked = new LinkedHashMap<>();
        installed.keySet().forEach(id -> {
            dependencies.put(id, new LinkedHashSet<>());
            requiredDependencies.put(id, new LinkedHashSet<>());
        });
        for (InstalledPluginCandidate candidate : installed.values()) {
            resolveDependencies(candidate, installed, dependencies.get(candidate.pluginId()),
                    requiredDependencies.get(candidate.pluginId()), blocked);
            addHints(candidate, installed.keySet(), dependencies);
        }
        propagateBlocks(requiredDependencies, blocked);
        List<List<PluginId>> cycles = cycles(dependencies, blocked.keySet());
        for (List<PluginId> cycle : cycles) {
            for (PluginId id : cycle.subList(0, cycle.size() - 1)) {
                blocked.computeIfAbsent(id, ignored -> new ArrayList<>()).add(
                        new DependencyBlock("dependency_cycle", id, formatCycle(cycle)));
            }
        }
        propagateBlocks(requiredDependencies, blocked);
        List<PluginId> enableOrder = topological(dependencies, blocked.keySet());
        List<PluginId> disableOrder = new ArrayList<>(enableOrder);
        Collections.reverse(disableOrder);
        return new DependencyResolution(enableOrder, disableOrder, dependencies, blocked, cycles);
    }

    /** Rebuilds all compatibility and ordering decisions from the current installed inventory. */
    public DependencyResolution reevaluate(Collection<InstalledPluginCandidate> candidates) {
        return resolve(candidates);
    }

    private static void resolveDependencies(
            InstalledPluginCandidate candidate,
            Map<PluginId, InstalledPluginCandidate> installed,
            Set<PluginId> dependencies,
            Set<PluginId> requiredDependencies,
            Map<PluginId, List<DependencyBlock>> blocked) {
        PluginId owner = candidate.pluginId();
        DependencyPolicy policy = candidate.descriptor().dependencies();
        policy.required().forEach((name, range) -> {
            PluginId dependency = new PluginId(name);
            InstalledPluginCandidate target = installed.get(dependency);
            if (target == null) {
                block(blocked, owner, "required_dependency_missing", dependency,
                        "required plugin is not installed");
            } else if (!range.includes(target.descriptor().version())) {
                block(blocked, owner, "required_dependency_incompatible", dependency,
                        "installed version " + target.descriptor().version().value()
                                + " does not satisfy " + range.value());
            } else {
                dependencies.add(dependency);
                requiredDependencies.add(dependency);
            }
        });
        policy.optional().forEach((name, range) -> {
            PluginId dependency = new PluginId(name);
            InstalledPluginCandidate target = installed.get(dependency);
            if (target != null && range.includes(target.descriptor().version())) {
                dependencies.add(dependency);
            }
        });
    }

    private static void addHints(
            InstalledPluginCandidate candidate,
            Set<PluginId> installed,
            Map<PluginId, Set<PluginId>> dependencies) {
        PluginId owner = candidate.pluginId();
        for (String name : candidate.descriptor().dependencies().loadAfter()) {
            PluginId target = new PluginId(name);
            if (installed.contains(target)) {
                dependencies.get(owner).add(target);
            }
        }
        for (String name : candidate.descriptor().dependencies().loadBefore()) {
            PluginId target = new PluginId(name);
            if (installed.contains(target)) {
                dependencies.get(target).add(owner);
            }
        }
    }

    private static void propagateBlocks(
            Map<PluginId, Set<PluginId>> dependencies, Map<PluginId, List<DependencyBlock>> blocked) {
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<PluginId, Set<PluginId>> entry : dependencies.entrySet()) {
                if (blocked.containsKey(entry.getKey())) {
                    continue;
                }
                for (PluginId dependency : entry.getValue()) {
                    if (blocked.containsKey(dependency)) {
                        block(blocked, entry.getKey(), "required_dependency_blocked", dependency,
                                "dependency is blocked");
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private static List<PluginId> topological(
            Map<PluginId, Set<PluginId>> dependencies, Set<PluginId> blocked) {
        Map<PluginId, Integer> indegree = new HashMap<>();
        Map<PluginId, Set<PluginId>> dependents = new HashMap<>();
        dependencies.keySet().stream().filter(id -> !blocked.contains(id)).forEach(id -> indegree.put(id, 0));
        dependencies.forEach((owner, required) -> {
            if (blocked.contains(owner)) {
                return;
            }
            for (PluginId dependency : required) {
                if (!blocked.contains(dependency)) {
                    indegree.compute(owner, (ignored, value) -> value + 1);
                    dependents.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(owner);
                }
            }
        });
        Queue<PluginId> ready = new PriorityQueue<>(Comparator.comparing(PluginId::value));
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<PluginId> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            PluginId next = ready.remove();
            order.add(next);
            for (PluginId dependent : dependents.getOrDefault(next, Set.of())) {
                int degree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(dependent);
                }
            }
        }
        return order;
    }

    private static List<List<PluginId>> cycles(
            Map<PluginId, Set<PluginId>> dependencies, Set<PluginId> alreadyBlocked) {
        List<List<PluginId>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PluginId start : dependencies.keySet()) {
            detect(start, dependencies, alreadyBlocked, new ArrayDeque<>(), new HashSet<>(), result, seen);
        }
        result.sort(Comparator.comparing(PluginDependencyGraph::formatCycle));
        return result;
    }

    private static void detect(
            PluginId current,
            Map<PluginId, Set<PluginId>> dependencies,
            Set<PluginId> blocked,
            java.util.Deque<PluginId> path,
            Set<PluginId> visiting,
            List<List<PluginId>> cycles,
            Set<String> seen) {
        if (blocked.contains(current)) {
            return;
        }
        if (visiting.contains(current)) {
            List<PluginId> pathList = new ArrayList<>(path);
            int offset = pathList.indexOf(current);
            List<PluginId> cycle = new ArrayList<>(pathList.subList(offset, pathList.size()));
            cycle.add(current);
            cycle = canonicalCycle(cycle);
            if (seen.add(formatCycle(cycle))) {
                cycles.add(cycle);
            }
            return;
        }
        visiting.add(current);
        path.addLast(current);
        dependencies.getOrDefault(current, Set.of()).stream()
                .sorted(Comparator.comparing(PluginId::value))
                .forEach(next -> detect(next, dependencies, blocked, path, visiting, cycles, seen));
        path.removeLast();
        visiting.remove(current);
    }

    private static List<PluginId> canonicalCycle(List<PluginId> cycle) {
        List<PluginId> open = cycle.subList(0, cycle.size() - 1);
        int minimum = 0;
        for (int index = 1; index < open.size(); index++) {
            if (open.get(index).value().compareTo(open.get(minimum).value()) < 0) {
                minimum = index;
            }
        }
        List<PluginId> canonical = new ArrayList<>();
        for (int index = 0; index < open.size(); index++) {
            canonical.add(open.get((minimum + index) % open.size()));
        }
        canonical.add(canonical.get(0));
        return canonical;
    }

    private static String formatCycle(List<PluginId> cycle) {
        return String.join(" -> ", cycle.stream().map(PluginId::value).toList());
    }

    private static void block(
            Map<PluginId, List<DependencyBlock>> blocked,
            PluginId owner,
            String code,
            PluginId dependency,
            String detail) {
        blocked.computeIfAbsent(owner, ignored -> new ArrayList<>())
                .add(new DependencyBlock(code, dependency, detail));
    }
}
