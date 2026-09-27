-- Private, derived ordinal index. The protected evaluation blob remains replay authority.
create table praxis_bulk.praxis_bulk_target_manifest (
    proposal_id uuid not null,
    evaluation_fingerprint text not null,
    ordinal integer not null,
    wire_identity jsonb not null,
    expected_version text not null,
    target_digest text not null,
    constraint praxis_bulk_target_manifest_pkey primary key (proposal_id, ordinal),
    constraint praxis_bulk_target_manifest_proposal_fkey
        foreign key (proposal_id) references praxis_bulk.praxis_bulk_proposal (proposal_id)
        on delete restrict,
    constraint praxis_bulk_target_manifest_evaluation_fkey
        foreign key (proposal_id, evaluation_fingerprint)
        references praxis_bulk.praxis_bulk_evaluation (proposal_id, evaluation_fingerprint)
        on delete restrict,
    constraint praxis_bulk_target_manifest_identity_key unique (proposal_id, wire_identity),
    constraint praxis_bulk_target_manifest_ordinal_check check (ordinal between 0 and 9999),
    constraint praxis_bulk_target_manifest_identity_check check (
        jsonb_typeof(wire_identity) in ('string', 'number')
        and (jsonb_typeof(wire_identity) <> 'number'
             or wire_identity::text ~ '^(0|[1-9][0-9]*)$|^-[1-9][0-9]*$')),
    constraint praxis_bulk_target_manifest_expected_version_check check (
        octet_length(expected_version) between 1 and 8388608 and btrim(expected_version) <> ''),
    constraint praxis_bulk_target_manifest_digest_check check (
        target_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- A deferred check makes an rc.136 writer fail at COMMIT: it cannot append the
-- matching manifest. The evaluation payload is already canonical JSON bytes.
create function praxis_bulk.require_complete_target_manifest()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
    v_count bigint;
    v_max integer;
    v_expected integer;
begin
    select count(*), max(m.ordinal) into v_count, v_max
      from praxis_bulk.praxis_bulk_target_manifest m
     where m.proposal_id = new.proposal_id
       and m.evaluation_fingerprint = new.evaluation_fingerprint;
    v_expected := jsonb_array_length((convert_from(new.payload, 'UTF8')::jsonb)->'targets');
    if v_expected is null or v_expected < 1 or v_expected > 10000
       or v_count <> v_expected or v_max <> v_expected - 1 then
        raise exception 'protected bulk evaluation requires a complete ordinal manifest'
            using errcode = '55000';
    end if;
    return null;
end;
$$;
create constraint trigger praxis_bulk_evaluation_require_manifest
    after insert on praxis_bulk.praxis_bulk_evaluation
    deferrable initially deferred
    for each row execute function praxis_bulk.require_complete_target_manifest();

create function praxis_bulk.reject_target_manifest_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if tg_op = 'DELETE' and current_user = 'praxis_bulk_retention_owner' then
        return old;
    end if;
    raise exception 'protected bulk target manifest is immutable' using errcode = '55000';
end;
$$;
create trigger praxis_bulk_target_manifest_immutable
    before update or delete on praxis_bulk.praxis_bulk_target_manifest
    for each row execute function praxis_bulk.reject_target_manifest_mutation();
create trigger praxis_bulk_target_manifest_guard_delete
    before delete on praxis_bulk.praxis_bulk_target_manifest
    for each row execute function praxis_bulk.guard_lifecycle_delete();

-- V5 transferred these SECURITY DEFINER functions to the isolated retention
-- owner. Membership is temporary and remains inside Flyway's DDL transaction.
do $$
begin
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I', current_user);
end;
$$;

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
            v_execution.proposal_id, v_execution.execution_id, v_execution.status,
            v_execution.terminal_at, v_now);
    delete from praxis_bulk.praxis_bulk_item_receipt where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_admission where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_allocation where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_allocation where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_execution where execution_id = p_execution_id;
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
    delete from praxis_bulk.praxis_bulk_target_manifest where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = p_proposal_id;
    return true;
end;
$$;
do $$
begin
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m
               join pg_catalog.pg_roles r on r.oid = m.roleid
               where r.rolname = 'praxis_bulk_retention_owner') then
        raise exception 'bulk retention owner membership was not fully revoked';
    end if;
end;
$$;
-- Retention owner only deletes through the two governed functions above.
grant select, delete on praxis_bulk.praxis_bulk_target_manifest to praxis_bulk_retention_owner;
revoke all on function praxis_bulk.require_complete_target_manifest() from public;
revoke all on function praxis_bulk.reject_target_manifest_mutation() from public;
