-- V10 durable cancellation: one database-stamped request and a fence for V9 writers.
alter table praxis_bulk.praxis_bulk_execution
    add column cancel_requested_at timestamp(6) with time zone;
alter table praxis_bulk.praxis_bulk_execution
    drop constraint praxis_bulk_execution_terminal_reason_check;
alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_terminal_reason_check check (
        (status = 'STOPPED') = (terminal_reason_code is not null)
        and (terminal_reason_code is null or terminal_reason_code in (
            'LEGACY_REASON_NOT_RECORDED', 'DEADLINE_EXCEEDED', 'AUTHORIZATION_REVOKED',
            'POLICY_BLOCKED', 'COMMON_GOVERNANCE_CHANGED', 'COMMON_GOVERNANCE_UNAVAILABLE',
            'DEPENDENCY_UNAVAILABLE', 'UNIT_ROLLED_BACK', 'RECOVERY_STOPPED',
            'EVALUATOR_UNAVAILABLE', 'STRUCTURAL_REVISION_CHANGED', 'CANCELLED_BY_USER')));
alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_cancel_shape_check check (
        terminal_reason_code is distinct from 'CANCELLED_BY_USER'
        or (cancel_requested_at is not null and next_ordinal < target_count
            and active_attempt_id is null));
alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_cancel_terminal_check check (
        cancel_requested_at is null or status <> 'STOPPED'
        or terminal_reason_code = 'CANCELLED_BY_USER');
alter table praxis_bulk.praxis_bulk_tombstone
    drop constraint praxis_bulk_tombstone_terminal_check;
alter table praxis_bulk.praxis_bulk_tombstone
    add constraint praxis_bulk_tombstone_terminal_check check
        (terminal_status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED', 'CANCELLED'));

create function praxis_bulk.protect_cancel_request()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if tg_op = 'INSERT' then
        if new.cancel_requested_at is not null then
            raise exception 'bulk cancel request is not allowed on execution insert' using errcode = '55000';
        end if;
        return new;
    end if;
    if old.cancel_requested_at is not null
       and new.cancel_requested_at is distinct from old.cancel_requested_at then
        raise exception 'bulk cancel request is immutable' using errcode = '55000';
    end if;
    if old.cancel_requested_at is null and new.cancel_requested_at is not null then
        if old.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
            raise exception 'bulk terminal execution cannot be cancelled' using errcode = '55000';
        end if;
        new.cancel_requested_at := clock_timestamp();
        new.updated_at := greatest(new.updated_at, new.cancel_requested_at);
        if new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
            new.terminal_at := greatest(new.terminal_at, new.cancel_requested_at);
        end if;
    end if;
    if new.cancel_requested_at is not null then
        if new.status = 'UNIT_IN_FLIGHT' and old.status <> 'UNIT_IN_FLIGHT' then
            raise exception 'bulk cancelled execution cannot admit a new attempt' using errcode = '55000';
        end if;
        if new.status = 'STOPPED' and old.status <> 'STOPPED'
           and new.terminal_reason_code <> 'CANCELLED_BY_USER' then
            raise exception 'bulk cancelled execution requires cancellation reconciliation' using errcode = '55000';
        end if;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_execution_protect_cancel
    before insert or update on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.protect_cancel_request();

create or replace function praxis_bulk.guard_terminal_evidence_insert()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
        where e.execution_id = new.execution_id for update;
    if not found or v_execution.cancel_requested_at is not null
       or v_execution.status <> 'UNIT_IN_FLIGHT'
       or new.unit_ordinal is distinct from v_execution.next_ordinal
       or new.unit_ordinal is distinct from v_execution.active_attempt_ordinal
       or new.target_digest is distinct from v_execution.active_target_digest
       or new.attempt_id is distinct from v_execution.active_attempt_id
       or new.owner_epoch is distinct from v_execution.active_attempt_epoch
       or new.owner_epoch is distinct from v_execution.owner_epoch
       or exists (select 1 from praxis_bulk.praxis_bulk_item_receipt r
                  where r.attempt_id = new.attempt_id
                     or (r.execution_id = new.execution_id
                         and (r.unit_ordinal = new.unit_ordinal
                              or r.target_digest = new.target_digest)))
       or exists (select 1 from praxis_bulk.praxis_bulk_admission a
                  where a.attempt_id = new.attempt_id
                     or (a.execution_id = new.execution_id
                         and (a.unit_ordinal = new.unit_ordinal
                              or a.target_digest = new.target_digest))) then
        raise exception 'bulk unit evidence differs from active fenced attempt or duplicates evidence'
            using errcode = '55000';
    end if;
    return new;
end;
$$;

-- Preserve the permanent replay denial record while retaining the safe public terminal kind.
do $$ begin execute pg_catalog.format('grant praxis_bulk_retention_owner to %I', current_user); end; $$;
create or replace function praxis_bulk.purge_terminal_execution(p_execution_id uuid)
returns boolean language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
declare
    v_execution praxis_bulk.praxis_bulk_execution%rowtype;
    v_allocation praxis_bulk.praxis_bulk_allocation%rowtype;
    v_deployment text;
    v_now timestamptz := clock_timestamp();
begin
    select b.deployment_id into v_deployment
    from praxis_bulk.praxis_bulk_execution e
    join praxis_bulk.praxis_bulk_namespace_binding b on b.namespace_id = e.namespace_id
    where e.execution_id = p_execution_id;
    if v_deployment is null then return false; end if;

    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.deployment_id = v_deployment and b.namespace_id =
            (select e.namespace_id from praxis_bulk.praxis_bulk_execution e where e.execution_id = p_execution_id)
        for share;
    perform 1 from praxis_bulk.praxis_bulk_operation_control c
        where (c.namespace_id, c.operation_id) =
            (select e.namespace_id, e.operation_id from praxis_bulk.praxis_bulk_execution e
             where e.execution_id = p_execution_id)
        for share;
    if not found then raise exception 'bulk operation control missing for retention' using errcode = '55000'; end if;
    perform 1 from praxis_bulk.praxis_bulk_deployment_bucket b
        where b.deployment_id = v_deployment for update;
    select a.* into v_allocation from praxis_bulk.praxis_bulk_allocation a
        where a.execution_id = p_execution_id;
    if not found then return false; end if;
    perform 1 from praxis_bulk.praxis_bulk_subject_bucket b
        where b.deployment_id = v_deployment
          and b.subject_scope_digest_version = v_allocation.subject_scope_digest_version
          and b.subject_scope_digest = v_allocation.subject_scope_digest for update;
    perform 1 from praxis_bulk.praxis_bulk_proposal p
        where p.proposal_id = (select e.proposal_id from praxis_bulk.praxis_bulk_execution e
            where e.execution_id = p_execution_id) for update;
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
        where e.execution_id = p_execution_id for update;
    if not found or v_execution.namespace_id <> v_allocation.namespace_id
       or v_allocation.deployment_id <> v_deployment
       or v_allocation.state <> 'RELEASED'
       or v_execution.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       or v_execution.terminal_at is null
       or v_execution.terminal_at > v_now - interval '30 days'
       or v_execution.active_attempt_id is not null
       or (v_execution.status = 'STOPPED' and v_execution.terminal_reason_code is null)
       or (v_execution.status = 'COMPLETED' and exists
           (select 1 from praxis_bulk.praxis_bulk_admission a
            where a.execution_id = p_execution_id))
       or (v_execution.status = 'COMPLETED_WITH_ERRORS' and not exists
           (select 1 from praxis_bulk.praxis_bulk_admission a
            where a.execution_id = p_execution_id))
       or not praxis_bulk.terminal_evidence_complete(
           p_execution_id, case when v_execution.status = 'STOPPED'
           then v_execution.next_ordinal else v_execution.target_count end) then
        return false;
    end if;

    insert into praxis_bulk.praxis_bulk_tombstone
        (namespace_id, authorization_scope_digest_version, authorization_scope_digest,
         resource_key, operation_id, idempotency_key_digest, proposal_id, execution_id,
         terminal_status, terminal_at, purged_at)
    values (v_execution.namespace_id, v_allocation.authorization_scope_digest_version,
            v_allocation.authorization_scope_digest, v_execution.resource_key,
            v_execution.operation_id, v_execution.idempotency_key_digest,
            v_execution.proposal_id, v_execution.execution_id,
            case when v_execution.status = 'STOPPED'
                       and v_execution.terminal_reason_code = 'CANCELLED_BY_USER'
                 then 'CANCELLED' else v_execution.status end,
            v_execution.terminal_at, v_now);
    delete from praxis_bulk.praxis_bulk_item_receipt where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_admission where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_allocation where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_allocation where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_execution where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_target_preview where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_preview_state where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_target_manifest where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = v_execution.proposal_id;
    return true;
end;
$$;
do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid
               where r.rolname='praxis_bulk_retention_owner') then
        raise exception 'bulk retention owner membership was not fully revoked';
    end if;
end; $$;
revoke all on function praxis_bulk.protect_cancel_request() from public;
