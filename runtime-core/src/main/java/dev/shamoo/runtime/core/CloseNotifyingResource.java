package dev.shamoo.runtime.core;

/** Resource that can notify an ownership registry after it closes naturally. */
public interface CloseNotifyingResource extends AutoCloseable {
    void onClosed(Runnable notification);

    @Override
    void close();
}
