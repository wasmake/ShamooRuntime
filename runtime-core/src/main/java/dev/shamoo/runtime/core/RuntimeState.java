package dev.shamoo.runtime.core;

/** Observable lifecycle state of a per-plugin runtime. */
public enum RuntimeState {
    CREATING,
    RUNNING,
    CLOSING,
    CLOSED,
    FAILED
}
