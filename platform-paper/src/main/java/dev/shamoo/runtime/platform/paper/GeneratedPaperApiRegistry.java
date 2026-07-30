package dev.shamoo.runtime.platform.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact public-member catalog loaded from the generated Paper API model. */
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.LooseCoupling"})
public final class GeneratedPaperApiRegistry {
    private static final String RESOURCE = "dev/shamoo/runtime/generated/paper/model.json";
    private final ClassLoader loader;
    private final Map<String, Member> members;
    private final Map<MemberName, List<Member>> overloads;
    private final Set<String> types;

    private GeneratedPaperApiRegistry(ClassLoader loader, Map<String, Member> members,
            Map<MemberName, List<Member>> overloads, Set<String> types) {
        this.loader = loader;
        this.members = Map.copyOf(members);
        Map<MemberName, List<Member>> copied = new HashMap<>();
        overloads.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        this.overloads = Map.copyOf(copied);
        this.types = Set.copyOf(types);
    }

    public static GeneratedPaperApiRegistry load(ClassLoader loader) throws IOException {
        Objects.requireNonNull(loader, "loader");
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("generated Paper model is missing: " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(input);
            if (root.required("schemaVersion").intValue() != 2
                    || !"paper".equals(root.required("platform").textValue())) {
                throw new IOException("generated Paper model has an incompatible schema or platform");
            }
            return parse(loader, root);
        }
    }

    static GeneratedPaperApiRegistry parse(ClassLoader loader, JsonNode root) throws IOException {
        Map<String, Member> members = new LinkedHashMap<>();
        Map<MemberName, List<Member>> overloads = new HashMap<>();
        Set<String> types = new java.util.LinkedHashSet<>();
        for (JsonNode declaration : root.required("declarations")) {
            String owner = declaration.required("javaName").textValue();
            types.add(owner);
            addMembers(owner, Kind.CONSTRUCTOR, "<init>", declaration.path("constructors"), members, overloads);
            for (JsonNode method : declaration.path("methods")) {
                add(owner, Kind.METHOD, method.required("name").textValue(), method, members, overloads);
            }
            for (JsonNode field : declaration.path("fields")) {
                add(owner, Kind.FIELD, field.required("name").textValue(), field, members, overloads);
            }
        }
        return new GeneratedPaperApiRegistry(loader, members, overloads, types);
    }

    private static void addMembers(String owner, Kind kind, String name, JsonNode values,
            Map<String, Member> members, Map<MemberName, List<Member>> overloads) throws IOException {
        for (JsonNode value : values) {
            add(owner, kind, name, value, members, overloads);
        }
    }

    private static void add(String owner, Kind kind, String name, JsonNode value,
            Map<String, Member> members, Map<MemberName, List<Member>> overloads) throws IOException {
        JsonNode descriptorNode = value.get("descriptor");
        JsonNode idNode = value.get("id");
        if (descriptorNode == null || idNode == null) {
            throw new IOException("generated Paper member lacks an exact JVM descriptor: " + owner + "#" + name);
        }
        String descriptor = descriptorNode.textValue();
        String expectedId = kind == Kind.FIELD ? owner + "#" + name + ":" + descriptor
                : owner + "#" + name + descriptor;
        if (!expectedId.equals(idNode.textValue())) {
            throw new IOException("generated Paper member has a noncanonical id: " + idNode.textValue());
        }
        Member member = new Member(expectedId, owner, kind, name, descriptor,
                value.path("static").asBoolean(false), value.path("readonly").asBoolean(false));
        if (members.putIfAbsent(member.id(), member) != null) {
            throw new IOException("duplicate generated Paper member id: " + member.id());
        }
        overloads.computeIfAbsent(new MemberName(owner, kind, name), ignored -> new ArrayList<>()).add(member);
    }

    public Member require(String owner, Kind kind, String name, String descriptor) {
        String id = switch (kind) {
            case CONSTRUCTOR -> owner + "#<init>" + descriptor;
            case METHOD -> owner + "#" + name + descriptor;
            case FIELD -> owner + "#" + name + ":" + descriptor;
        };
        Member member = members.get(id);
        if (member != null && member.kind() == kind) {
            return member;
        }
        if (kind != Kind.CONSTRUCTOR) {
            List<Member> inherited = overloads(owner, kind, name).stream()
                    .filter(candidate -> candidate.descriptor().equals(descriptor)).toList();
            if (!inherited.isEmpty()) {
                return inherited.getFirst();
            }
        }
        throw new IllegalArgumentException("unknown generated Paper member: " + id);
    }

    public List<Member> overloads(String owner, Kind kind, String name) {
        if (!types.contains(owner)) {
            throw new IllegalArgumentException("unknown generated Paper type: " + owner);
        }
        Map<String, Member> result = new LinkedHashMap<>();
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new java.util.HashSet<>();
        pending.add(requireType(owner));
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!visited.add(type)) {
                continue;
            }
            overloads.getOrDefault(new MemberName(type.getName(), kind, name), List.of())
                    .forEach(member -> result.putIfAbsent(member.id(), member));
            List<Class<?>> interfaces = java.util.Arrays.stream(type.getInterfaces())
                    .sorted(Comparator.comparing(Class::getName)).toList();
            pending.addAll(interfaces);
            if (type.getSuperclass() != null) {
                pending.add(type.getSuperclass());
            }
        }
        return List.copyOf(result.values());
    }

    public String exposedType(Class<?> implementation) {
        Objects.requireNonNull(implementation, "implementation");
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new java.util.HashSet<>();
        pending.add(implementation);
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!visited.add(type)) {
                continue;
            }
            if (types.contains(type.getName())) {
                return type.getName();
            }
            List<Class<?>> interfaces = java.util.Arrays.stream(type.getInterfaces())
                    .sorted(Comparator.comparingInt(GeneratedPaperApiRegistry::typePreference)
                            .thenComparing(Class::getName)).toList();
            pending.addAll(interfaces);
            if (type.getSuperclass() != null) {
                pending.add(type.getSuperclass());
            }
        }
        // Scanner-external values remain opaque handles. They can be passed back into generated
        // Paper members but cannot be used as reflective invocation owners.
        return implementation.getName();
    }

    private static int typePreference(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.bukkit.") ? 0
                : name.startsWith("io.papermc.paper.") ? 1
                : name.startsWith("com.destroystokyo.paper.") ? 2
                : name.startsWith("net.kyori.adventure.") ? 3 : 4;
    }

    public Class<?> requireType(String javaName) {
        if (!types.contains(javaName)) {
            throw new IllegalArgumentException("unknown generated Paper type: " + javaName);
        }
        try {
            Class<?> result = Class.forName(javaName, false, loader);
            if (!Modifier.isPublic(result.getModifiers())) {
                throw new IllegalArgumentException("generated Paper type is not public: " + javaName);
            }
            return result;
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("generated Paper type cannot be linked: " + javaName, exception);
        }
    }

    public int memberCount() {
        return members.size();
    }

    public enum Kind {
        CONSTRUCTOR,
        METHOD,
        FIELD
    }

    public record Member(String id, String owner, Kind kind, String name, String descriptor,
            boolean staticMember, boolean readOnly) {
    }

    private record MemberName(String owner, Kind kind, String name) {
    }
}
