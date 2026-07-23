package dev.shamoo.runtime.javet;

/** Receives asynchronous runtime errors on the owning runtime thread. */
@FunctionalInterface
public interface RuntimeErrorReporter {
    void report(RuntimeUnhandledError error);
}
