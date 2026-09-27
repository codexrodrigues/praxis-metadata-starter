-- V11 binds each safe V9 preview item to its immutable manifest row for bounded reads.
-- The final parent column has no default: a V9 writer omitting it cannot commit.
-- ADD DEFAULT fills historical rows without invoking V9's immutable UPDATE trigger.
-- DROP DEFAULT in the same DDL transaction fences V9 writers after commit.
alter table praxis_bulk.praxis_bulk_preview_state
    add column integrity_version integer not null default 11;
alter table praxis_bulk.praxis_bulk_preview_state
    alter column integrity_version drop default;
alter table praxis_bulk.praxis_bulk_preview_state
    add constraint praxis_bulk_preview_state_integrity_version_check check (integrity_version = 11);

create table praxis_bulk.praxis_bulk_preview_item_integrity (
    proposal_id uuid not null,
    ordinal integer not null,
    digest_version integer not null,
    item_digest text not null,
    constraint praxis_bulk_preview_item_integrity_pkey primary key (proposal_id, ordinal),
    constraint praxis_bulk_preview_item_integrity_preview_fkey foreign key (proposal_id, ordinal)
        references praxis_bulk.praxis_bulk_target_preview (proposal_id, ordinal) on delete restrict,
    constraint praxis_bulk_preview_item_integrity_version_check check (digest_version = 1),
    constraint praxis_bulk_preview_item_integrity_digest_check check (item_digest ~ '^sha256:[0-9a-f]{64}$')
);
create trigger praxis_bulk_preview_item_integrity_immutable before update or delete
    on praxis_bulk.praxis_bulk_preview_item_integrity for each row
    execute function praxis_bulk.reject_preview_mutation();
create trigger praxis_bulk_preview_item_integrity_guard_delete before delete
    on praxis_bulk.praxis_bulk_preview_item_integrity for each row
    execute function praxis_bulk.guard_lifecycle_delete();

create table praxis_bulk.praxis_bulk_preview_integrity_bootstrap (
    bootstrap_version integer primary key check (bootstrap_version = 11),
    phase text not null check (phase in ('PENDING', 'COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_preview_integrity_bootstrap values (11, 'PENDING');
revoke all on praxis_bulk.praxis_bulk_preview_integrity_bootstrap from public;

-- Flyway commits this fence in the same DDL transaction as the PENDING marker.
-- Runtime cannot see or alter the marker, and neither V9 nor V11 may admit
-- a new parent until the validated bootstrap commits COMPLETE.
create function praxis_bulk.require_complete_preview_integrity_bootstrap()
returns trigger language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
begin
    if (select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
        where bootstrap_version = 11) is distinct from 'COMPLETE' then
        raise exception 'bulk preview integrity bootstrap is pending' using errcode = '55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_preview_state_integrity_guard_insert before insert
    on praxis_bulk.praxis_bulk_preview_state for each row
    execute function praxis_bulk.require_complete_preview_integrity_bootstrap();
revoke all on function praxis_bulk.require_complete_preview_integrity_bootstrap() from public;

create function praxis_bulk.require_complete_preview_item_integrity()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
    v_state praxis_bulk.praxis_bulk_preview_state%rowtype;
    v_count bigint;
begin
    select * into v_state from praxis_bulk.praxis_bulk_preview_state
     where proposal_id = new.proposal_id and evaluation_fingerprint = new.evaluation_fingerprint;
    if not found or v_state.integrity_version <> 11 then
        raise exception 'bulk evaluation requires V11 preview integrity' using errcode = '55000';
    end if;
    select count(*) into v_count from praxis_bulk.praxis_bulk_preview_item_integrity
     where proposal_id = new.proposal_id;
    if (v_state.projection_state = 'COMPLETE' and v_count <> v_state.target_count)
       or (v_state.projection_state = 'UNAVAILABLE' and v_count <> 0)
       or v_state.projection_state not in ('COMPLETE', 'UNAVAILABLE') then
        raise exception 'bulk preview item integrity is incomplete' using errcode = '55000';
    end if;
    return null;
end;
$$;
create constraint trigger praxis_bulk_evaluation_require_preview_item_integrity
    after insert on praxis_bulk.praxis_bulk_evaluation deferrable initially deferred
    for each row execute function praxis_bulk.require_complete_preview_item_integrity();
revoke all on function praxis_bulk.require_complete_preview_item_integrity() from public;

-- Only the isolated retention definer may DELETE; V11 redefines the governed
-- retention functions to remove leaves before preview rows. RESTRICT makes
-- the ordering explicit and rejects accidental parent removal.
grant select, delete on praxis_bulk.praxis_bulk_preview_item_integrity
    to praxis_bulk_retention_owner;

-- V11 retention functions explicitly delete integrity leaves before previews.
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
    delete from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_target_preview where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_preview_state where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_target_manifest where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = v_execution.proposal_id;
    return true;
end;
$$;

create or replace function praxis_bulk.expire_unconsumed_proposal(p_proposal_id uuid)
returns boolean language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
declare
    v_allocation praxis_bulk.praxis_bulk_allocation%rowtype;
    v_deployment text;
    v_proposal praxis_bulk.praxis_bulk_proposal%rowtype;
begin
    select b.deployment_id into v_deployment
    from praxis_bulk.praxis_bulk_proposal p
    join praxis_bulk.praxis_bulk_namespace_binding b on b.namespace_id = p.namespace_id
    where p.proposal_id = p_proposal_id;
    if v_deployment is null then return false; end if;
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.namespace_id = (select p.namespace_id from praxis_bulk.praxis_bulk_proposal p
            where p.proposal_id = p_proposal_id) for share;
    perform 1 from praxis_bulk.praxis_bulk_operation_control c
        where (c.namespace_id, c.operation_id) =
            (select p.namespace_id, p.operation_id from praxis_bulk.praxis_bulk_proposal p
             where p.proposal_id = p_proposal_id)
        for share;
    if not found then raise exception 'bulk operation control missing for expiry' using errcode = '55000'; end if;
    perform 1 from praxis_bulk.praxis_bulk_deployment_bucket b
        where b.deployment_id = v_deployment for update;
    select a.* into v_allocation from praxis_bulk.praxis_bulk_allocation a
        where a.proposal_id = p_proposal_id;
    if not found then return false; end if;
    perform 1 from praxis_bulk.praxis_bulk_subject_bucket b
        where b.deployment_id = v_deployment
          and b.subject_scope_digest_version = v_allocation.subject_scope_digest_version
          and b.subject_scope_digest = v_allocation.subject_scope_digest for update;
    select p.* into v_proposal from praxis_bulk.praxis_bulk_proposal p
        where p.proposal_id = p_proposal_id for update;
    if not found or v_proposal.expires_at > clock_timestamp()
       or v_allocation.state <> 'PENDING'
       or v_allocation.namespace_id <> v_proposal.namespace_id
       or v_allocation.deployment_id <> v_deployment
       or exists (select 1 from praxis_bulk.praxis_bulk_execution e
                  where e.proposal_id = p_proposal_id) then return false; end if;
    update praxis_bulk.praxis_bulk_allocation
        set state = 'RELEASED', released_at = clock_timestamp(), release_reason = 'PROPOSAL_EXPIRED'
        where proposal_id = p_proposal_id and state = 'PENDING';
    delete from praxis_bulk.praxis_bulk_allocation where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_target_preview where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_preview_state where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_target_manifest where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = p_proposal_id;
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
