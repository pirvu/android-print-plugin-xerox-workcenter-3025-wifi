package com.xerox3025.printplugin;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;

/** One token per job. Closing owned I/O also unblocks a pending read/write. */
final class JobControl {
    private boolean cancelled;
    private final Set<Closeable> active = new HashSet<>();

    synchronized boolean isCancelled() { return cancelled; }

    synchronized void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Cancelled by user");
        }
    }

    synchronized void attach(Closeable resource) throws IOException {
        if (cancelled) {
            resource.close();
            check();
        }
        active.add(resource);
    }

    synchronized void detach(Closeable resource) { active.remove(resource); }

    void cancel() {
        Closeable[] resources;
        synchronized (this) {
            cancelled = true;
            resources = active.toArray(new Closeable[0]);
            active.clear();
        }
        for (Closeable resource : resources) {
            try { resource.close(); } catch (IOException ignored) { }
        }
    }
}
