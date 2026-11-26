package org.praxisplatform.uischema.bulk;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;

/**
 * Single-node asynchronous coordinator for namespace-wide bulk publication and reconciliation
 * jobs. Enforces at most one active job per namespace, retains bounded completed history, and
 * surfaces live batch progress alongside persistent V21 audit entries.
 */
public final class BulkPublicationJobService implements DisposableBean {
    private static final int MAX_COMPLETED_HISTORY = 200;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final BulkOperationLifecycle lifecycle;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, MutableJobState> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> activeJobsByNamespace = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> completedHistory = new ConcurrentLinkedDeque<>();

    public BulkPublicationJobService(BulkOperationLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        AtomicInteger threadSequence = new AtomicInteger();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, "praxis-bulk-publication-job-" + threadSequence.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        });
    }

    /** Submits an asynchronous batch job targeting this lifecycle's configured namespace. */
    public BulkPublicationJobSnapshot submit(BulkPublicationJobIntent intent, BulkLifecycleAuditContext audit) {
        return submit(lifecycle.namespace(), intent, audit);
    }

    /** Submits an asynchronous batch job targeting {@code namespace}. */
    public BulkPublicationJobSnapshot submit(
            String namespace, BulkPublicationJobIntent intent, BulkLifecycleAuditContext audit) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(intent, "intent");
        if (!lifecycle.namespace().equals(namespace)) {
            throw new IllegalArgumentException(
                    "Bulk publication job namespace does not match configured lifecycle namespace");
        }
        BulkLifecycleAuditContext effectiveAudit = audit != null ? audit : BulkLifecycleAuditContext.system();
        UUID jobId = UUID.randomUUID();
        if (activeJobsByNamespace.putIfAbsent(namespace, jobId) != null) {
            throw new BulkPublicationConflictException(
                    "A bulk publication job is already active for namespace " + namespace);
        }
        MutableJobState state = new MutableJobState(
                jobId, namespace, intent, effectiveAudit.actor(), effectiveAudit.realm(),
                effectiveAudit.correlationId(), Instant.now());
        jobs.put(jobId, state);
        try {
            executor.submit(() -> executeJob(jobId, namespace, intent, effectiveAudit, state));
        } catch (RejectedExecutionException rejected) {
            activeJobsByNamespace.remove(namespace, jobId);
            jobs.remove(jobId, state);
            throw new BulkLifecycleUnavailableException("Bulk publication job executor is unavailable", rejected);
        } catch (RuntimeException | Error startupFailure) {
            activeJobsByNamespace.remove(namespace, jobId);
            jobs.remove(jobId, state);
            throw startupFailure;
        }
        return state.snapshot();
    }

    /** Looks up a queued, running, or retained completed job by ID. */
    public Optional<BulkPublicationJobSnapshot> findJob(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        MutableJobState state = jobs.get(jobId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    /** Returns up to {@code limit} known jobs sorted newest-first (1..200). */
    public List<BulkPublicationJobSnapshot> listJobs(int limit) {
        if (limit < 1 || limit > MAX_COMPLETED_HISTORY) {
            throw new IllegalArgumentException("Job history limit must be between 1 and " + MAX_COMPLETED_HISTORY);
        }
        return jobs.values().stream()
                .map(MutableJobState::snapshot)
                .sorted(Comparator.comparing(BulkPublicationJobSnapshot::createdAt).reversed()
                        .thenComparing(BulkPublicationJobSnapshot::jobId, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    /** Reads persistent V21 lifecycle audit entries for the configured namespace, newest first. */
    public List<BulkLifecycleAuditEntry> listAuditEntries(int limit) {
        return lifecycle.listAuditEntries(limit);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private void executeJob(
            UUID jobId,
            String namespace,
            BulkPublicationJobIntent intent,
            BulkLifecycleAuditContext audit,
            MutableJobState state) {
        state.markRunning();
        BulkBatchProgressListener listener = state::updateProgress;
        try {
            BulkNamespaceBatchResult result = switch (intent) {
                case PUBLISH_ALL -> lifecycle.publishAll(namespace, audit, listener);
                case RECONCILE_ALL -> lifecycle.reconcileAll(namespace, audit, listener);
            };
            state.markCompleted(result);
        } catch (Throwable failure) {
            state.markFailed(classifyErrorCode(failure), sanitizeErrorMessage(failure));
        } finally {
            activeJobsByNamespace.remove(namespace, jobId);
            recordCompletedHistory(jobId);
        }
    }

    private void recordCompletedHistory(UUID jobId) {
        completedHistory.addLast(jobId);
        while (completedHistory.size() > MAX_COMPLETED_HISTORY) {
            UUID evicted = completedHistory.pollFirst();
            if (evicted == null) {
                break;
            }
            if (!activeJobsByNamespace.containsValue(evicted)) {
                jobs.remove(evicted);
            }
        }
    }

    private static String classifyErrorCode(Throwable failure) {
        if (failure instanceof BulkPublicationConflictException) {
            return "BULK_PUBLICATION_CONFLICT";
        }
        if (failure instanceof BulkLifecycleUnavailableException) {
            return "BULK_LIFECYCLE_UNAVAILABLE";
        }
        if (failure instanceof BulkContractDiagnosticException) {
            return "BULK_CONTRACT_DIAGNOSTIC";
        }
        if (failure instanceof IllegalArgumentException) {
            return "BULK_PUBLICATION_INVALID_REQUEST";
        }
        return "BULK_PUBLICATION_JOB_FAILED";
    }

    private static String sanitizeErrorMessage(Throwable failure) {
        String raw = failure.getMessage();
        if (raw == null || raw.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String sanitized = raw.trim().replaceAll("[\\p{Cntrl}\\r\\n]+", " ");
        if (sanitized.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return sanitized.length() > MAX_ERROR_MESSAGE_LENGTH
                ? sanitized.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : sanitized;
    }

    private static final class MutableJobState {
        private final UUID jobId;
        private final String namespace;
        private final BulkPublicationJobIntent intent;
        private final String actor;
        private final String realm;
        private final String correlationId;
        private final Instant createdAt;

        private BulkPublicationJobStatus status = BulkPublicationJobStatus.QUEUED;
        private String phase = "QUEUED";
        private int totalOperations;
        private int processedCount;
        private int succeededCount;
        private int skippedCount;
        private int failedCount;
        private Instant startedAt;
        private Instant finishedAt;
        private BulkNamespaceBatchResult result;
        private String errorCode;
        private String errorMessage;

        private MutableJobState(
                UUID jobId,
                String namespace,
                BulkPublicationJobIntent intent,
                String actor,
                String realm,
                String correlationId,
                Instant createdAt) {
            this.jobId = jobId;
            this.namespace = namespace;
            this.intent = intent;
            this.actor = actor;
            this.realm = realm;
            this.correlationId = correlationId;
            this.createdAt = createdAt;
        }

        private synchronized void markRunning() {
            this.status = BulkPublicationJobStatus.RUNNING;
            this.phase = "STARTING";
            this.startedAt = Instant.now();
        }

        private synchronized void updateProgress(
                String phase, int total, int processed, int succeeded, int skipped, int failed) {
            if (phase != null && !phase.isBlank()) {
                this.phase = phase;
            }
            this.totalOperations = total;
            this.processedCount = processed;
            this.succeededCount = succeeded;
            this.skippedCount = skipped;
            this.failedCount = failed;
        }

        private synchronized void markCompleted(BulkNamespaceBatchResult result) {
            this.status = BulkPublicationJobStatus.COMPLETED;
            this.phase = "COMPLETED";
            this.result = result;
            if (result != null) {
                this.totalOperations = result.totalOperations();
                this.processedCount = result.processedCount();
                this.succeededCount = result.succeededCount();
                this.skippedCount = result.skippedCount();
                this.failedCount = result.failedCount();
            }
            this.finishedAt = Instant.now();
        }

        private synchronized void markFailed(String errorCode, String errorMessage) {
            this.status = BulkPublicationJobStatus.FAILED;
            this.phase = "FAILED";
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.finishedAt = Instant.now();
        }

        private synchronized BulkPublicationJobSnapshot snapshot() {
            return new BulkPublicationJobSnapshot(
                    jobId, namespace, intent, status, phase,
                    totalOperations, processedCount, succeededCount, skippedCount, failedCount,
                    actor, realm, correlationId, createdAt, startedAt, finishedAt,
                    result, errorCode, errorMessage);
        }
    }
}
