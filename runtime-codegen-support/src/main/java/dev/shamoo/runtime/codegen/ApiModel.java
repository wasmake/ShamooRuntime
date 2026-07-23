package dev.shamoo.runtime.codegen;

import java.util.List;
import java.util.Objects;

/** Deterministic class-file model used by generators without loading platform classes. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public record ApiModel(String platform, List<ApiType> types, Inventory inventory) {
    public ApiModel(String platform, List<ApiType> types) {
        this(platform, types, new Inventory(types.size(), types.stream().mapToLong(type ->
                type.fields().size() + type.methods().size()).sum(), 0,
                types.stream().flatMap(type -> type.methods().stream()).mapToLong(method ->
                        method.exceptions().size()).sum()));
    }

    public ApiModel {
        Objects.requireNonNull(platform, "platform");
        types = types.stream().sorted().toList();
    }

    public record Inventory(long eligibleTypes, long eligibleMembers, long eligibleEvents, long eligibleExceptions) {
    }

    @Override
    public List<ApiType> types() {
        return List.copyOf(types);
    }

    /** A public API type and all supported public/protected members present in its class file. */
    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record ApiType(
            String name,
            String signature,
            String superName,
            List<String> interfaces,
            int access,
            boolean record,
            boolean enumType,
            boolean annotationType,
            boolean functionalInterface,
            boolean deprecated,
            List<ApiAnnotation> annotations,
            List<ApiRecordComponent> recordComponents,
            List<ApiField> fields,
            List<ApiMethod> methods) implements Comparable<ApiType> {
        public ApiType {
            interfaces = sorted(interfaces);
            annotations = sorted(annotations);
            recordComponents = sorted(recordComponents);
            fields = sorted(fields);
            methods = sorted(methods);
        }

        @Override
        public int compareTo(ApiType other) {
            return name.compareTo(other.name);
        }

        @Override
        public List<String> interfaces() {
            return List.copyOf(interfaces);
        }

        @Override
        public List<ApiAnnotation> annotations() {
            return List.copyOf(annotations);
        }

        @Override
        public List<ApiRecordComponent> recordComponents() {
            return List.copyOf(recordComponents);
        }

        @Override
        public List<ApiField> fields() {
            return List.copyOf(fields);
        }

        @Override
        public List<ApiMethod> methods() {
            return List.copyOf(methods);
        }
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record ApiAnnotation(String descriptor, boolean typeUse, boolean visible)
            implements Comparable<ApiAnnotation> {
        @Override
        public int compareTo(ApiAnnotation other) {
            int result = descriptor.compareTo(other.descriptor);
            result = result == 0 ? Boolean.compare(typeUse, other.typeUse) : result;
            return result == 0 ? Boolean.compare(visible, other.visible) : result;
        }
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record ApiRecordComponent(String name, String descriptor, String signature,
            List<ApiAnnotation> annotations) implements Comparable<ApiRecordComponent> {
        public ApiRecordComponent {
            annotations = sorted(annotations);
        }

        @Override
        public int compareTo(ApiRecordComponent other) {
            return (name + descriptor).compareTo(other.name + other.descriptor);
        }

        @Override
        public List<ApiAnnotation> annotations() {
            return List.copyOf(annotations);
        }
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record ApiField(String name, String descriptor, String signature, int access, Object constant,
            boolean deprecated,
            List<ApiAnnotation> annotations) implements Comparable<ApiField> {
        public ApiField {
            annotations = sorted(annotations);
        }

        @Override
        public int compareTo(ApiField other) {
            return (name + descriptor).compareTo(other.name + other.descriptor);
        }

        @Override
        public List<ApiAnnotation> annotations() {
            return List.copyOf(annotations);
        }
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record ApiMethod(String name, String descriptor, String signature, int access, boolean deprecated,
            boolean varargs, boolean defaultMethod, List<String> parameterNames, List<String> exceptions,
            List<ApiAnnotation> annotations)
            implements Comparable<ApiMethod> {
        public ApiMethod {
            parameterNames = List.copyOf(parameterNames);
            exceptions = sorted(exceptions);
            annotations = sorted(annotations);
        }

        @Override
        public int compareTo(ApiMethod other) {
            return (name + descriptor).compareTo(other.name + other.descriptor);
        }

        @Override
        public List<String> exceptions() {
            return List.copyOf(exceptions);
        }

        @Override
        public List<String> parameterNames() {
            return List.copyOf(parameterNames);
        }

        @Override
        public List<ApiAnnotation> annotations() {
            return List.copyOf(annotations);
        }
    }

    private static <T extends Comparable<? super T>> List<T> sorted(List<T> values) {
        return values.stream().sorted().toList();
    }
}
