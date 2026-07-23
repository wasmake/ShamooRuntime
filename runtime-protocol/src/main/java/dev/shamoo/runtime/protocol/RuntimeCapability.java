package dev.shamoo.runtime.protocol;

/** Host-enforced capabilities relevant to manifest admission. */
public enum RuntimeCapability {
    NODE_BUILTINS,
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    NETWORK,
    WORKERS,
    CHILD_PROCESS,
    NATIVE_ADDONS
}
