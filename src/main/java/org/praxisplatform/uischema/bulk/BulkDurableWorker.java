package org.praxisplatform.uischema.bulk;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Explicitly composed protected worker. It neither accepts jobs nor publishes ASYNC capability.
 * Durable PostgreSQL claims remain the sole authority; local handles are not a pending queue.
 */
final class BulkDurableWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BulkDurableWorker.class);
    private static final int SCAN_QUANTUM = 8;
    private static final int WRAP_ROUNDS = 8;
    private static final Duration IDLE = Duration.ofMillis(250);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);
    static final int PHASE = Integer.MAX_VALUE - 1024;

    record Handler(String resourceKey, CanonicalOperationRef operation,
            BulkUnitAdmissionCallback admission, BulkUnitMutationCallback mutation) {
        Handler {
            BulkContractChecks.text(resourceKey, "resourceKey");
            Objects.requireNonNull(operation, "operation");
            BulkContractChecks.text(operation.operationId(), "operationId");
            BulkContractChecks.text(operation.path(), "operation path");
            BulkContractChecks.text(operation.method(), "operation method");
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(mutation, "mutation");
        }
        boolean matches(BulkFingerprintContext scope) {
            return resourceKey.equals(scope.resourceKey()) && operation.equals(scope.operationRef());
        }
        @Override public String toString() { return "Handler[protected]"; }
    }

    record Binding(JdbcBulkDurableExecution kernel, List<Handler> handlers) {
        Binding {
            Objects.requireNonNull(kernel, "kernel").workerBindingIdentity();
            handlers = List.copyOf(handlers);
            if (handlers.isEmpty()) throw new IllegalArgumentException("Trusted callbacks are required");
            for (int i = 0; i < handlers.size(); i++)
                for (int j = 0; j < i; j++)
                    if (handlers.get(i).resourceKey().equals(handlers.get(j).resourceKey())
                            && handlers.get(i).operation().equals(handlers.get(j).operation()))
                        throw new IllegalArgumentException("Duplicate operation callbacks");
        }
        @Override public String toString() { return "Binding[protected]"; }
    }

    private static final class State {
        final Binding binding;
        JdbcBulkDurableExecution.WorkerQueueHint cursor;
        int rounds;
        long retryAfter;
        BulkFingerprintContext scope;
        Handler handler;
        BulkExecutionReservation owned;
        State(Binding binding) { this.binding = binding; }
    }

    private final List<Binding> bindings;
    private final Object monitor = new Object();
    private final java.util.Set<Runnable> stopCallbacks =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Thread thread;
    private boolean stopping;
    private boolean joining;
    private String owner;

    BulkDurableWorker(List<Binding> bindings) {
        this.bindings = List.copyOf(bindings);
        if (this.bindings.size() > 8) throw new IllegalArgumentException("At most eight local bindings");
        for (int i = 0; i < this.bindings.size(); i++)
            for (int j = 0; j < i; j++)
                if (this.bindings.get(i).kernel().workerBindingIdentity()
                        .equals(this.bindings.get(j).kernel().workerBindingIdentity()))
                    throw new IllegalArgumentException("Duplicate capacity binding");
    }

    /** Protected lifecycle diagnostic; STOPPING never means the transaction has ended. */
    boolean isStopping() { synchronized (monitor) { return stopping; } }

    @Override public int getPhase() { return PHASE; }
    @Override public boolean isAutoStartup() { return !bindings.isEmpty(); }
    @Override public boolean isRunning() {
        synchronized (monitor) { return thread != null && thread.isAlive(); }
    }
    @Override public void start() {
        synchronized (monitor) {
            if (bindings.isEmpty() || stopping || thread != null && thread.isAlive()) return;
            owner = "bulk-worker-" + UUID.randomUUID();
            String startedOwner = owner;
            thread = new Thread(() -> run(startedOwner), "praxis-bulk-worker");
            thread.start();
        }
    }
    private boolean authorizeNext() {
        synchronized (monitor) { return !stopping; }
    }

    @Override public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        boolean immediately;
        synchronized (monitor) {
            immediately = thread == null || !thread.isAlive() && !joining;
            if (!immediately) {
                stopCallbacks.add(callback);
                stopping = true;
                monitor.notifyAll();
                if (!joining) {
                    joining = true;
                    Thread stoppedThread = thread;
                    // One transient joiner; no JDBC, shared executor, polling or false timeout ACK.
                    Thread.ofVirtual().name("praxis-bulk-worker-stop").start(() -> completeStop(stoppedThread));
                }
            }
        }
        if (immediately) callback.run();
    }
    private void completeStop(Thread stoppedThread) {
        boolean interrupted = false;
        for (;;) {
            try { stoppedThread.join(); break; }
            catch (InterruptedException error) { interrupted = true; }
        }
        var delivered = Collections.newSetFromMap(new IdentityHashMap<Runnable, Boolean>());
        for (;;) {
            List<Runnable> callbacks;
            synchronized (monitor) {
                callbacks = stopCallbacks.stream().filter(value -> !delivered.contains(value)).toList();
                if (callbacks.isEmpty()) {
                    stopCallbacks.clear(); stopping = false; joining = false;
                    monitor.notifyAll(); break;
                }
                delivered.addAll(callbacks);
            }
            for (Runnable callback : callbacks) {
                try { callback.run(); }
                catch (RuntimeException | Error error) { LOG.warn("Bulk worker stop callback failed"); }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Override public void stop() {
        var completed = new java.util.concurrent.CountDownLatch(1);
        stop(completed::countDown);
        try {
            if (!completed.await(STOP_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                LOG.warn("Bulk worker shutdown remains pending");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    private void run(String startedOwner) {
        List<State> states = bindings.stream().map(State::new).toList();
        try {
            while (authorizeNext()) {
                boolean progressed = false;
                for (State state : states) {
                    if (!authorizeNext()) break;
                    if (state.retryAfter == 0 || System.nanoTime() - state.retryAfter >= 0) {
                        try {
                            // Maintenance failure cannot terminalize an unrelated owned job.
                            progressed |= scan(state, startedOwner);
                            state.retryAfter = 0;
                        } catch (RuntimeException error) {
                            state.retryAfter = System.nanoTime() + ERROR_BACKOFF.toNanos();
                            LOG.warn("Bulk worker selection deferred after protected failure");
                        }
                    }
                    if (state.owned != null && authorizeNext()) {
                        try { advanceOne(state, startedOwner); progressed = true; }
                        catch (RuntimeException error) {
                            reconcile(state, startedOwner);
                            LOG.warn("Bulk worker owned execution stopped after protected failure");
                        }
                    }
                }
                if (!progressed) {
                    synchronized (monitor) {
                        if (!stopping) monitor.wait(IDLE.toMillis());
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            for (State state : states) reconcile(state, startedOwner);
        }
    }

    private boolean scan(State state, String startedOwner) {
        boolean progressed = false;
        boolean wrapped = false;
        if (++state.rounds > WRAP_ROUNDS) { state.cursor = null; state.rounds = 1; }
        for (int i = 0; i < SCAN_QUANTUM && authorizeNext(); i++) {
            var next = state.binding.kernel().workerNextQueued(state.cursor);
            if (next.isEmpty()) {
                boolean hadCursor = state.cursor != null;
                state.cursor = null; state.rounds = 0;
                // Revisit older queued hints before the next owned unit, within this quantum.
                // An initially empty queue and a second exhaustion both stop immediately.
                if (hadCursor && !wrapped) { wrapped = true; continue; }
                break;
            }
            var hint = next.orElseThrow();
            state.cursor = hint; // Advance before decoding a corrupt/suspended head.
            try {
                var scope = state.binding.kernel().workerContext(hint.executionId());
                if (state.binding.kernel().workerExpireQueued(scope, hint.executionId())) {
                    progressed = true; continue;
                }
                if (state.owned != null) continue;
                Handler handler = state.binding.handlers().stream().filter(value -> value.matches(scope))
                        .findFirst().orElse(null);
                if (handler == null) continue;
                var active = state.binding.kernel().workerAvailableActiveToken();
                if (active.isEmpty() || !authorizeNext()) continue;
                var claim = state.binding.kernel().claim(scope, hint.executionId(), startedOwner, active.orElseThrow());
                if (claim.isEmpty()) continue;
                state.scope = scope; state.handler = handler; state.owned = claim.orElseThrow();
                progressed = true;
                if (state.owned.replayed() || !authorizeNext()) reconcile(state, startedOwner);
            } catch (BulkDurableExecutionException error) {
                // Lost hint/invalid head is not ownership. Keep bounded progress past it.
                // In particular, a poisoned queued job cannot recover an independent handle.
                LOG.debug("Bulk worker queued hint unavailable");
            }
        }
        return progressed;
    }

    private void advanceOne(State state, String startedOwner) {
        int ordinal = state.owned.nextOrdinal();
        var result = state.binding.kernel().executeUnit(state.owned.control(), ordinal,
                state.handler.admission(), state.handler.mutation());
        if (!result.replayed() && result.status() == BulkDurableExecutionStatus.RUNNING
                && result.durableResultPresent() && result.execution().nextOrdinal() == ordinal + 1) {
            state.owned = new BulkExecutionReservation(result.execution(), false);
        } else reconcile(state, startedOwner);
    }
    private void reconcile(State state, String startedOwner) {
        if (state.owned == null) return;
        try { state.binding.kernel().workerRecoverOwned(state.scope, state.owned, startedOwner); }
        catch (RuntimeException error) { LOG.warn("Bulk worker owned reconciliation remains unresolved"); }
        finally { state.owned = null; state.scope = null; state.handler = null; }
    }
}
