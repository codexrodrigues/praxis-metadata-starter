-- Ordered private worker selection; no new authority, grants or bootstrap.
-- V19 owner bootstrap must be COMPLETE before the canonical migrator reaches V20.
CREATE INDEX praxis_bulk_execution_worker_queue_idx
    ON praxis_bulk.praxis_bulk_execution USING btree (namespace_id, created_at, execution_id)
    WHERE execution_mode = 'ASYNC' AND status = 'QUEUED';
