-- Durable EXPLICIT/SYNC/PER_ITEM execution control. V1 and V2 remain immutable history.
alter table praxis_bulk.praxis_bulk_evaluation
    add constraint praxis_bulk_evaluation_proposal_fingerprint_key
    unique (proposal_id, evaluation_fingerprint);

create table praxis_bulk.praxis_bulk_execution (
    execution_id uuid not null,
    proposal_id uuid not null,
    namespace_id text not null,
    subject_id text not null,
    resource_key text not null,
    operation_id text not null,
    idempotency_key_digest text not null,
    reservation_fingerprint text not null,
    input_fingerprint text not null,
    evaluation_fingerprint text not null,
    structural_revision text not null,
    owner_id text not null,
    owner_epoch bigint not null,
    status text not null,
    next_ordinal integer not null,
    target_count integer not null,
    deadline_at timestamp(6) with time zone not null,
    active_attempt_id uuid,
    active_attempt_ordinal integer,
    active_target_digest text,
    active_attempt_epoch bigint,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    terminal_at timestamp(6) with time zone,
    constraint praxis_bulk_execution_pkey primary key (execution_id),
    constraint praxis_bulk_execution_proposal_key unique (proposal_id),
    constraint praxis_bulk_execution_scoped_idempotency_key unique
        (namespace_id, subject_id, resource_key, operation_id, idempotency_key_digest),
    constraint praxis_bulk_execution_evaluation_fkey
        foreign key (proposal_id, evaluation_fingerprint)
        references praxis_bulk.praxis_bulk_evaluation (proposal_id, evaluation_fingerprint),
    constraint praxis_bulk_execution_namespace_nonblank_check check (btrim(namespace_id) <> ''),
    constraint praxis_bulk_execution_subject_nonblank_check check (btrim(subject_id) <> ''),
    constraint praxis_bulk_execution_resource_nonblank_check check (btrim(resource_key) <> ''),
    constraint praxis_bulk_execution_operation_nonblank_check check (btrim(operation_id) <> ''),
    constraint praxis_bulk_execution_revision_nonblank_check check (btrim(structural_revision) <> ''),
    constraint praxis_bulk_execution_owner_nonblank_check check (btrim(owner_id) <> ''),
    constraint praxis_bulk_execution_digest_format_check check
        (idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_execution_reservation_fingerprint_check check
        (reservation_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_execution_input_fingerprint_check check
        (input_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_execution_evaluation_fingerprint_check check
        (evaluation_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_execution_epoch_check check (owner_epoch >= 1),
    constraint praxis_bulk_execution_progress_check check
        (target_count between 1 and 10000 and next_ordinal between 0 and target_count),
    constraint praxis_bulk_execution_deadline_check check (deadline_at > created_at),
    constraint praxis_bulk_execution_status_check check
        (status in ('RUNNING', 'UNIT_IN_FLIGHT', 'UNIT_COMMITTED_PENDING_ACK',
                    'COMPLETED', 'STOPPED', 'RECONCILIATION_REQUIRED')),
    constraint praxis_bulk_execution_attempt_shape_check check (
        ((active_attempt_id is null) = (active_attempt_ordinal is null))
        and ((active_attempt_id is null) = (active_target_digest is null))
        and ((active_attempt_id is null) = (active_attempt_epoch is null))
        and (active_attempt_id is null or
             (active_attempt_ordinal = next_ordinal
              and active_attempt_ordinal between 0 and target_count - 1
              and active_attempt_epoch between 1 and owner_epoch
              and active_target_digest ~ '^sha256:[0-9a-f]{64}$'))),
    constraint praxis_bulk_execution_state_shape_check check (
        (status = 'RUNNING' and active_attempt_id is null and next_ordinal < target_count and terminal_at is null)
        or (status in ('UNIT_IN_FLIGHT', 'UNIT_COMMITTED_PENDING_ACK')
            and active_attempt_id is not null and terminal_at is null)
        or (status = 'COMPLETED' and active_attempt_id is null
            and next_ordinal = target_count and terminal_at is not null)
        or (status = 'STOPPED' and terminal_at is not null)
        or (status = 'RECONCILIATION_REQUIRED' and terminal_at is null))
);

create table praxis_bulk.praxis_bulk_item_receipt (
    execution_id uuid not null,
    unit_ordinal integer not null,
    target_digest text not null,
    expected_version text not null,
    attempt_id uuid not null,
    owner_epoch bigint not null,
    outcome text not null,
    confirmed_at timestamp(6) with time zone not null,
    constraint praxis_bulk_item_receipt_pkey primary key (execution_id, unit_ordinal),
    constraint praxis_bulk_item_receipt_target_key unique (execution_id, target_digest),
    constraint praxis_bulk_item_receipt_attempt_key unique (attempt_id),
    constraint praxis_bulk_item_receipt_execution_fkey foreign key (execution_id)
        references praxis_bulk.praxis_bulk_execution (execution_id),
    constraint praxis_bulk_item_receipt_ordinal_check check (unit_ordinal >= 0),
    constraint praxis_bulk_item_receipt_target_digest_check check
        (target_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_item_receipt_expected_version_nonblank_check check
        (btrim(expected_version) <> ''),
    constraint praxis_bulk_item_receipt_epoch_check check (owner_epoch >= 1),
    constraint praxis_bulk_item_receipt_outcome_check check (outcome in ('CONFIRMED', 'UNCHANGED'))
);

create function praxis_bulk.protect_praxis_bulk_execution_binding()
returns trigger language plpgsql as $$
begin
    if new.execution_id is distinct from old.execution_id
       or new.proposal_id is distinct from old.proposal_id
       or new.namespace_id is distinct from old.namespace_id
       or new.subject_id is distinct from old.subject_id
       or new.resource_key is distinct from old.resource_key
       or new.operation_id is distinct from old.operation_id
       or new.idempotency_key_digest is distinct from old.idempotency_key_digest
       or new.reservation_fingerprint is distinct from old.reservation_fingerprint
       or new.input_fingerprint is distinct from old.input_fingerprint
       or new.evaluation_fingerprint is distinct from old.evaluation_fingerprint
       or new.structural_revision is distinct from old.structural_revision
       or new.target_count is distinct from old.target_count
       or new.deadline_at is distinct from old.deadline_at
       or new.created_at is distinct from old.created_at then
        raise exception 'praxis_bulk.praxis_bulk_execution binding is immutable' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_execution_protect_binding
before update on praxis_bulk.praxis_bulk_execution
for each row execute function praxis_bulk.protect_praxis_bulk_execution_binding();

create function praxis_bulk.reject_praxis_bulk_item_receipt_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'praxis_bulk.praxis_bulk_item_receipt is immutable' using errcode = '55000';
end;
$$;

create trigger praxis_bulk_item_receipt_reject_mutation
before update or delete on praxis_bulk.praxis_bulk_item_receipt
for each row execute function praxis_bulk.reject_praxis_bulk_item_receipt_mutation();
