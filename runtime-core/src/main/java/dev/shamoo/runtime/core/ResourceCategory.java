package dev.shamoo.runtime.core;

/** Categories exposed in plugin resource ownership reports. */
public enum ResourceCategory {
    GENERIC,
    LISTENER,
    COMMAND,
    TASK,
    TIMER,
    FILE,
    WATCHER,
    PROXY,
    SERVICE,
    MESSAGE,
    PENDING_INVOCATION
}
