package dev.shamoo.runtime.javet;

import java.util.List;

/** Explicit host callback; only instances registered by name are visible to JavaScript. */
@FunctionalInterface
public interface HostFunction {
    Object invoke(List<Object> arguments) throws Exception;

    default Object invoke(List<Object> arguments, boolean admitted) throws Exception {
        return invoke(arguments);
    }
}
