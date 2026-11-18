package com.deepseekharness.app.phone.a11y;

/**
 * Reference-counted event subscription for one accessibility connection. Only transitions hold
 * this monitor; a tree read, gesture or wait must never hold it (teardown runs on the UI thread).
 */
final class UiEventScope {
    interface Listener { void setListening(boolean active); }

    private final Listener listener;
    private long generation;
    private int users;
    private boolean connected;

    UiEventScope(Listener listener) { this.listener = listener; }

    synchronized void connect() {
        connected = false;
        generation++;
        users = 0;
        listener.setListening(false);
        connected = true;
    }

    synchronized long enter() throws InterruptedException {
        if (!connected) throw new InterruptedException("Accessibility connection has closed");
        if (users == 0) {
            try {
                listener.setListening(true);
            } catch (RuntimeException | Error failure) {
                try { listener.setListening(false); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        users++;
        return generation;
    }

    synchronized void exit(long token) {
        if (token == generation) {
            if (users <= 0) throw new IllegalStateException("Unbalanced UI event scope");
            users--;
        }
        // Also clear again after an operation that was already in flight when close() ran.
        // An old connection's finally must not disable a new connection's active operation.
        if (users == 0) listener.setListening(false);
    }

    synchronized void close() {
        connected = false;
        listener.setListening(false);
    }
}
