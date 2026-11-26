package org.praxisplatform.uischema.bulk;

/** Receives progress updates during namespace-wide bulk lifecycle batch operations. */
@FunctionalInterface
public interface BulkBatchProgressListener {
    BulkBatchProgressListener NOOP = (phase, total, processed, succeeded, skipped, failed) -> {};

    void onProgress(String phase, int total, int processed, int succeeded, int skipped, int failed);
}
