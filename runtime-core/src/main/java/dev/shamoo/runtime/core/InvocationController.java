package dev.shamoo.runtime.core;

/** Runtime-facing invocation admission boundary. */
public interface InvocationController {
    InvocationAdmission.Lease admit();

    InvocationSnapshot snapshot();
}
