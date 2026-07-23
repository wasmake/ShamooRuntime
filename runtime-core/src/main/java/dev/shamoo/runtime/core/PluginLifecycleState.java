package dev.shamoo.runtime.core;

/** Stable administrative states for one installed plugin candidate. */
public enum PluginLifecycleState {
    DISCOVERED,
    BLOCKED,
    LOADING,
    LOADED,
    LOAD_FAILED,
    ENABLING,
    ENABLED,
    ENABLE_FAILED,
    READYING,
    READY,
    READY_FAILED,
    DRAINING,
    DRAIN_FAILED,
    DISABLING,
    DISABLED,
    DISABLE_FAILED,
    UNLOADING,
    UNLOADED,
    UNLOAD_FAILED,
    QUARANTINED
}
