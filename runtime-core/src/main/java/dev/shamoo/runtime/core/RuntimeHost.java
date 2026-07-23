package dev.shamoo.runtime.core;

import java.util.concurrent.CompletionStage;

/** Platform services available to a runtime without exposing server-specific types. */
public interface RuntimeHost {
    String platformName();

    System.Logger logger();

    CompletionStage<Void> dispatch(Runnable task);
}
