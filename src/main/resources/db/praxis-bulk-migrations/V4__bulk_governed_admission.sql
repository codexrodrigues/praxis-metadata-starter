-- Governed PER_ITEM no-mutation decisions. V1-V3 remain immutable history.
-- A receipt continues to prove a domain commit; an admission records only a
-- definite target-local outcome that did not invoke the mutation callback.
alter table praxis_bulk.praxis_bulk_execution
    add column terminal_reason_code text;

-- Admission and its domain transaction share one absolute five-second budget. Existing active
-- attempts from the previous kernel have no such deadline and are handled by recovery only.
alter table praxis_bulk.praxis_bulk_execution
    add column active_unit_deadline_at timestamp(6) with time zone;

-- V3 did not retain why a STOPPED execution stopped. Do not infer a cause.
-- V3 could retain a rolled-back in-flight marker on STOPPED; it is not a live attempt.
update praxis_bulk.praxis_bulk_execution
set terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED',
    active_attempt_id = null,
    active_attempt_ordinal = null,
    active_target_digest = null,
    active_attempt_epoch = null
where status = 'STOPPED';

alter table praxis_bulk.praxis_bulk_execution
    drop constraint praxis_bulk_execution_status_check,
    drop constraint praxis_bulk_execution_state_shape_check;

alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_status_check check
        (status in ('RUNNING', 'UNIT_IN_FLIGHT', 'UNIT_COMMITTED_PENDING_ACK',
                    'COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED', 'RECONCILIATION_REQUIRED')),
    add constraint praxis_bulk_execution_state_shape_check check (
        (status = 'RUNNING' and active_attempt_id is null and next_ordinal < target_count and terminal_at is null)
        or (status in ('UNIT_IN_FLIGHT', 'UNIT_COMMITTED_PENDING_ACK')
            and active_attempt_id is not null and terminal_at is null)
        or (status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') and active_attempt_id is null
            and next_ordinal = target_count and terminal_at is not null)
        or (status = 'STOPPED' and terminal_at is not null and active_attempt_id is null
            and active_attempt_ordinal is null and active_target_digest is null
            and active_attempt_epoch is null)
        or (status = 'RECONCILIATION_REQUIRED' and terminal_at is null)),
    add constraint praxis_bulk_execution_terminal_reason_check check (
        (status = 'STOPPED') = (terminal_reason_code is not null)
        and (terminal_reason_code is null or terminal_reason_code in (
            'LEGACY_REASON_NOT_RECORDED', 'DEADLINE_EXCEEDED', 'AUTHORIZATION_REVOKED',
            'POLICY_BLOCKED', 'COMMON_GOVERNANCE_CHANGED', 'COMMON_GOVERNANCE_UNAVAILABLE',
            'DEPENDENCY_UNAVAILABLE', 'UNIT_ROLLED_BACK', 'RECOVERY_STOPPED',
            'EVALUATOR_UNAVAILABLE', 'STRUCTURAL_REVISION_CHANGED')));

alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_active_unit_deadline_check check (
        active_unit_deadline_at is null
        or (active_attempt_id is not null and active_unit_deadline_at <= deadline_at)
    );

create function praxis_bulk.protect_praxis_bulk_terminal_reason()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
        end if;
    else
        if old.status = 'STOPPED'
           and new.terminal_reason_code is distinct from old.terminal_reason_code then
            raise exception 'terminal stop reason is immutable' using errcode = '55000';
        end if;
        if old.status is distinct from 'STOPPED'
           and new.status = 'STOPPED'
           and new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
        end if;
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_execution_protect_terminal_reason
before insert or update on praxis_bulk.praxis_bulk_execution
for each row execute function praxis_bulk.protect_praxis_bulk_terminal_reason();

create table praxis_bulk.praxis_bulk_admission (
    execution_id uuid not null,
    unit_ordinal integer not null,
    target_digest text not null,
    expected_version text not null,
    attempt_id uuid not null,
    owner_epoch bigint not null,
    outcome text not null,
    reason_code text not null,
    recorded_at timestamp(6) with time zone not null,
    constraint praxis_bulk_admission_pkey primary key (execution_id, unit_ordinal),
    constraint praxis_bulk_admission_target_key unique (execution_id, target_digest),
    constraint praxis_bulk_admission_attempt_key unique (attempt_id),
    constraint praxis_bulk_admission_execution_fkey foreign key (execution_id)
        references praxis_bulk.praxis_bulk_execution (execution_id),
    constraint praxis_bulk_admission_ordinal_check check (unit_ordinal >= 0),
    constraint praxis_bulk_admission_target_digest_check check
        (target_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_admission_expected_version_nonblank_check check
        (btrim(expected_version) <> ''),
    constraint praxis_bulk_admission_epoch_check check (owner_epoch >= 1),
    constraint praxis_bulk_admission_outcome_reason_check check (
        (outcome = 'DENIED' and reason_code = 'TARGET_DENIED')
        or (outcome = 'INVALID' and reason_code in ('TARGET_NOT_FOUND', 'TARGET_INVALID'))
        or (outcome = 'CONFLICT' and reason_code in
            ('TARGET_VERSION_CONFLICT', 'TARGET_STATE_CONFLICT', 'TARGET_DEPENDENCY_CHANGED')))
);

-- Existing V3 receipts remain replayable; new kernel writes always persist this field.
alter table praxis_bulk.praxis_bulk_item_receipt
    add column unit_deadline_at timestamp(6) with time zone,
    add constraint praxis_bulk_item_receipt_unit_deadline_check check (
        unit_deadline_at is null or confirmed_at < unit_deadline_at
    );

create function praxis_bulk.reject_praxis_bulk_admission_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'praxis_bulk.praxis_bulk_admission is immutable' using errcode = '55000';
end;
$$;

create trigger praxis_bulk_admission_reject_mutation
before update or delete on praxis_bulk.praxis_bulk_admission
for each row execute function praxis_bulk.reject_praxis_bulk_admission_mutation();
