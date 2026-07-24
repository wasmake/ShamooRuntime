package dev.shamoo.runtime.core;

/** Consumer behavior when a service provider generation is transactionally replaced. */
public enum DependentReloadPolicy {
    KEEP_RUNNING,
    RELOAD
}
