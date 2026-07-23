package dev.shamoo.runtime.codegen;

import dev.shamoo.runtime.codegen.ApiModel.ApiAnnotation;
import dev.shamoo.runtime.codegen.ApiModel.ApiField;
import dev.shamoo.runtime.codegen.ApiModel.ApiMethod;
import dev.shamoo.runtime.codegen.ApiModel.ApiRecordComponent;
import dev.shamoo.runtime.codegen.ApiModel.ApiType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

/** Scans public class-file surface directly with ASM and never initializes artifact classes. */
@SuppressWarnings("PMD.NullAssignment")
public final class AsmApiScanner {
    private static final int API = Opcodes.ASM9;

    public ApiModel scan(String platform, List<Path> artifacts, Predicate<String> included) throws IOException {
        Map<String, ApiType> types = new HashMap<>();
        long eligibleMembers = 0;
        for (Path artifact : artifacts.stream().sorted().toList()) {
            try (JarFile jar = new JarFile(artifact.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
                for (var entry : jar.versionedStream().filter(item -> item.getName().endsWith(".class")).toList()) {
                    try (var input = jar.getInputStream(entry)) {
                        ApiType type = read(input.readAllBytes(), included);
                        if (type != null) {
                            types.put(type.name(), type);
                            eligibleMembers += type.fields().size() + type.methods().size();
                        }
                    }
                }
            }
        }
        Map<String, ApiType> complete = new HashMap<>(types);
        List<ApiType> normalized = types.values().stream().map(type -> withFunctionalFlag(type,
                isFunctional(type, complete, new java.util.HashSet<>()))).toList();
        long events = expectedEvents(platform, normalized);
        long exceptions = normalized.stream().flatMap(type -> type.methods().stream())
                .mapToLong(method -> method.exceptions().size()).sum();
        return new ApiModel(platform, normalized,
                new ApiModel.Inventory(types.size(), eligibleMembers, events, exceptions));
    }

    private static long expectedEvents(String platform, List<ApiType> types) {
        Map<String, ApiType> indexed = new HashMap<>();
        types.forEach(type -> indexed.put(type.name(), type));
        return types.stream().filter(type -> !type.annotationType() && !type.enumType())
                .filter(type -> (type.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0)
                .filter(type -> "paper".equals(platform) ? extendsType(type, "org.bukkit.event.Event", indexed)
                        : !type.name().contains("$") && type.name().startsWith("com.velocitypowered.api.event.")
                        && type.name().endsWith("Event") && !type.name().contains(".annotation."))
                .count();
    }

    private static boolean extendsType(ApiType type, String expected, Map<String, ApiType> types) {
        Set<String> visited = new java.util.HashSet<>();
        String current = type.superName();
        while (current != null && visited.add(current)) {
            if (expected.equals(current)) {
                return true;
            }
            ApiType parent = types.get(current);
            current = parent == null ? null : parent.superName();
        }
        return false;
    }

    private static ApiType withFunctionalFlag(ApiType type, boolean functional) {
        return new ApiType(type.name(), type.signature(), type.superName(), type.interfaces(), type.access(),
                type.record(), type.enumType(), type.annotationType(), functional, type.deprecated(),
                type.annotations(), type.recordComponents(), type.fields(), type.methods());
    }

    private static boolean isFunctional(ApiType type, Map<String, ApiType> types, Set<String> visited) {
        if ((type.access() & Opcodes.ACC_INTERFACE) == 0 || !visited.add(type.name())) {
            return false;
        }
        Set<String> signatures = new java.util.HashSet<>();
        collectAbstractMethods(type, types, new java.util.HashSet<>(), signatures);
        return signatures.size() == 1;
    }

    private static void collectAbstractMethods(ApiType type, Map<String, ApiType> types, Set<String> visited,
            Set<String> signatures) {
        if (!visited.add(type.name())) {
            return;
        }
        type.methods().stream().filter(method -> (method.access() & Opcodes.ACC_ABSTRACT) != 0)
                .filter(method -> !Set.of("equals", "hashCode", "toString").contains(method.name()))
                .forEach(method -> signatures.add(method.name() + method.descriptor()));
        type.interfaces().stream().map(types::get).filter(java.util.Objects::nonNull)
                .forEach(parent -> collectAbstractMethods(parent, types, visited, signatures));
    }

    private static ApiType read(byte[] bytecode, Predicate<String> included) {
        TypeVisitor visitor = new TypeVisitor(included);
        new ClassReader(bytecode).accept(visitor,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.result();
    }

    private static final class TypeVisitor extends ClassVisitor {
        private final Predicate<String> included;
        private final List<ApiAnnotation> annotations = new ArrayList<>();
        private final List<ComponentData> components = new ArrayList<>();
        private final List<FieldData> fields = new ArrayList<>();
        private final List<MethodData> methods = new ArrayList<>();
        private String name;
        private String signature;
        private String superName;
        private List<String> interfaces;
        private int access;

        TypeVisitor(Predicate<String> included) {
            super(API);
            this.included = included;
        }

        @Override
        public void visit(int version, int classAccess, String internalName, String classSignature,
                String parent, String[] implemented) {
            name = internalName.replace('/', '.');
            access = classAccess;
            signature = classSignature;
            superName = external(parent);
            interfaces = implemented == null ? List.of()
                    : java.util.Arrays.stream(implemented).map(TypeVisitor::external).toList();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(new ApiAnnotation(descriptor, false, visible));
            return null;
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
            annotations.add(new ApiAnnotation(descriptor, true, visible));
            return null;
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String componentName, String descriptor,
                String componentSignature) {
            List<ApiAnnotation> values = new ArrayList<>();
            components.add(new ComponentData(componentName, descriptor, componentSignature, values));
            return annotationCollector(values);
        }

        @Override
        public FieldVisitor visitField(int fieldAccess, String fieldName, String descriptor, String fieldSignature,
                Object value) {
            if ((fieldAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                    || (fieldAccess & Opcodes.ACC_SYNTHETIC) != 0) {
                return null;
            }
            List<ApiAnnotation> values = new ArrayList<>();
            fields.add(new FieldData(fieldName, descriptor, fieldSignature, fieldAccess, value,
                    (fieldAccess & Opcodes.ACC_DEPRECATED) != 0, values));
            return fieldAnnotationCollector(values);
        }

        @Override
        public MethodVisitor visitMethod(int methodAccess, String methodName, String descriptor, String methodSignature,
                String[] thrown) {
            if ((methodAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 || "<clinit>".equals(methodName)
                    || (methodAccess & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                return null;
            }
            List<ApiAnnotation> values = new ArrayList<>();
            List<String> parameterNames = new ArrayList<>();
            boolean defaultMethod = (access & Opcodes.ACC_INTERFACE) != 0
                    && (methodAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
            methods.add(new MethodData(methodName, descriptor, methodSignature, methodAccess,
                    (methodAccess & Opcodes.ACC_DEPRECATED) != 0, (methodAccess & Opcodes.ACC_VARARGS) != 0,
                    defaultMethod, parameterNames, thrown == null ? List.of() : List.of(thrown), values));
            return methodAnnotationCollector(values, parameterNames);
        }

        ApiType result() {
            if (name == null || (access & Opcodes.ACC_PUBLIC) == 0 || (access & Opcodes.ACC_SYNTHETIC) != 0
                    || !included.test(name)) {
                return null;
            }
            List<ApiRecordComponent> apiComponents = components.stream().map(component -> new ApiRecordComponent(
                    component.name, component.descriptor, component.signature, component.annotations)).toList();
            List<ApiField> apiFields = fields.stream().map(field -> new ApiField(field.name, field.descriptor,
                    field.signature, field.access, field.constant, field.deprecated, field.annotations)).toList();
            List<ApiMethod> apiMethods = methods.stream().map(method -> new ApiMethod(method.name, method.descriptor,
                    method.signature, method.access, method.deprecated, method.varargs, method.defaultMethod,
                    method.parameterNames, method.exceptions, method.annotations)).toList();
            return new ApiType(name, signature, superName, interfaces, access, (access & Opcodes.ACC_RECORD) != 0,
                    (access & Opcodes.ACC_ENUM) != 0, (access & Opcodes.ACC_ANNOTATION) != 0, false,
                    (access & Opcodes.ACC_DEPRECATED) != 0, annotations, apiComponents, apiFields, apiMethods);
        }

        private static String external(String value) {
            return value == null ? null : value.replace('/', '.');
        }

        private record ComponentData(String name, String descriptor, String signature,
                List<ApiAnnotation> annotations) {
        }

        private record FieldData(String name, String descriptor, String signature, int access, Object constant,
                boolean deprecated,
                List<ApiAnnotation> annotations) {
        }

        private record MethodData(String name, String descriptor, String signature, int access, boolean deprecated,
                boolean varargs, boolean defaultMethod, List<String> parameterNames, List<String> exceptions,
                List<ApiAnnotation> annotations) {
        }
    }

    private static RecordComponentVisitor annotationCollector(List<ApiAnnotation> values) {
        return new RecordComponentVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                values.add(new ApiAnnotation(descriptor, false, visible));
                return null;
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor,
                    boolean visible) {
                values.add(new ApiAnnotation(descriptor, true, visible));
                return null;
            }
        };
    }

    private static FieldVisitor fieldAnnotationCollector(List<ApiAnnotation> values) {
        return new FieldVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                values.add(new ApiAnnotation(descriptor, false, visible));
                return null;
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor,
                    boolean visible) {
                values.add(new ApiAnnotation(descriptor, true, visible));
                return null;
            }
        };
    }

    private static MethodVisitor methodAnnotationCollector(List<ApiAnnotation> values, List<String> parameterNames) {
        return new MethodVisitor(API) {
            @Override
            public void visitParameter(String name, int access) {
                parameterNames.add(name);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                values.add(new ApiAnnotation(descriptor, false, visible));
                return null;
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor,
                    boolean visible) {
                TypeReference reference = new TypeReference(typeRef);
                String target = switch (reference.getSort()) {
                    case TypeReference.METHOD_RETURN -> "return:";
                    case TypeReference.METHOD_FORMAL_PARAMETER ->
                        "parameter[" + reference.getFormalParameterIndex() + "]:";
                    default -> "type:";
                };
                values.add(new ApiAnnotation(target + descriptor, true, visible));
                return null;
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                values.add(new ApiAnnotation("parameter[" + parameter + "]:" + descriptor, true, visible));
                return null;
            }
        };
    }
}
