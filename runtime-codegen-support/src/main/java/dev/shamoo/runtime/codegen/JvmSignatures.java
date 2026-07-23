package dev.shamoo.runtime.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/** Converts JVM generic signatures into deterministic Java-like type strings. */
final class JvmSignatures {
    private JvmSignatures() {
    }

    static String field(String signature, String descriptor) {
        if (signature == null) {
            return descriptor(Type.getType(descriptor));
        }
        List<String> result = new ArrayList<>();
        new SignatureReader(signature).acceptType(new TypeText(result::add));
        return result.getFirst();
    }

    static MethodTypes method(String signature, String descriptor) {
        if (signature == null) {
            return new MethodTypes(List.of(), java.util.Arrays.stream(Type.getArgumentTypes(descriptor))
                    .map(JvmSignatures::descriptor).toList(), descriptor(Type.getReturnType(descriptor)));
        }
        List<TypeParameter> typeParameters = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        List<String> returns = new ArrayList<>();
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            private TypeParameter current;

            @Override
            public void visitFormalTypeParameter(String name) {
                current = new TypeParameter(name, null);
                typeParameters.add(current);
            }

            @Override
            public SignatureVisitor visitClassBound() {
                int index = typeParameters.size() - 1;
                return new TypeText(bound -> typeParameters.set(index, new TypeParameter(current.name(), bound)));
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return visitClassBound();
            }

            @Override
            public SignatureVisitor visitParameterType() {
                return new TypeText(parameters::add);
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return new TypeText(returns::add);
            }
        });
        return new MethodTypes(typeParameters, parameters, returns.getFirst());
    }

    static ClassTypes declaration(String signature, String superName, List<String> interfaces) {
        if (signature == null) {
            return new ClassTypes(List.of(), superName == null ? List.of() : List.of(superName), interfaces);
        }
        List<TypeParameter> typeParameters = new ArrayList<>();
        List<String> inherited = new ArrayList<>();
        List<String> implemented = new ArrayList<>();
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            private TypeParameter current;

            @Override
            public void visitFormalTypeParameter(String name) {
                current = new TypeParameter(name, null);
                typeParameters.add(current);
            }

            @Override
            public SignatureVisitor visitClassBound() {
                int index = typeParameters.size() - 1;
                return new TypeText(bound -> typeParameters.set(index, new TypeParameter(current.name(), bound)));
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return visitClassBound();
            }

            @Override
            public SignatureVisitor visitSuperclass() {
                return new TypeText(inherited::add);
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new TypeText(implemented::add);
            }
        });
        return new ClassTypes(typeParameters, inherited, implemented);
    }

    static String descriptor(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "boolean";
            case Type.CHAR -> "char";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            case Type.ARRAY -> descriptor(type.getElementType()) + "[]".repeat(type.getDimensions());
            case Type.OBJECT -> type.getClassName();
            default -> throw new IllegalArgumentException("unsupported JVM type " + type);
        };
    }

    record TypeParameter(String name, String bound) {
    }

    record MethodTypes(List<TypeParameter> typeParameters, List<String> parameters, String returns) {
    }

    record ClassTypes(List<TypeParameter> typeParameters, List<String> inherited, List<String> implemented) {
    }

    @SuppressWarnings("PMD.AvoidStringBufferField")
    private static final class TypeText extends SignatureVisitor {
        private final Consumer<String> result;
        private final StringBuilder value = new StringBuilder();
        private boolean arguments;
        private boolean completed;

        private TypeText(Consumer<String> result) {
            super(Opcodes.ASM9);
            this.result = result;
        }

        @Override
        public void visitBaseType(char descriptor) {
            finish(JvmSignatures.descriptor(Type.getType(String.valueOf(descriptor))));
        }

        @Override
        public void visitTypeVariable(String name) {
            finish(name);
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return new TypeText(type -> finish(type + "[]"));
        }

        @Override
        public void visitClassType(String name) {
            value.append(name.replace('/', '.'));
        }

        @Override
        public void visitInnerClassType(String name) {
            closeArguments();
            value.append('$').append(name);
        }

        @Override
        public void visitTypeArgument() {
            beginArgument();
            value.append('?');
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            beginArgument();
            return new TypeText(type -> {
                if (wildcard == EXTENDS) {
                    value.append("? extends ");
                } else if (wildcard == SUPER) {
                    value.append("? super ");
                }
                value.append(type);
            });
        }

        @Override
        public void visitEnd() {
            closeArguments();
            finish(value.toString());
        }

        private void beginArgument() {
            if (!arguments) {
                value.append('<');
                arguments = true;
            } else {
                value.append(", ");
            }
        }

        private void closeArguments() {
            if (arguments) {
                value.append('>');
                arguments = false;
            }
        }

        private void finish(String type) {
            if (!completed) {
                completed = true;
                result.accept(type);
            }
        }
    }
}
