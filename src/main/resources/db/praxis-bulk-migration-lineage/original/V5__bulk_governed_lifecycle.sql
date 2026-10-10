-- S4c physical lifecycle. Flyway creates only the structure. The explicit migrator
-- must bind every historical namespace to a supplied deployment and backfill the
-- controls, digests, buckets and allocations atomically before runtime is enabled.
-- Neither deployment identity nor subject/scope digests can be inferred by SQL.

create table praxis_bulk.praxis_bulk_namespace_binding (
    namespace_id text primary key,
    deployment_id text not null,
    bound_at timestamptz not null,
    constraint praxis_bulk_namespace_binding_namespace_check check (btrim(namespace_id) <> ''),
    constraint praxis_bulk_namespace_binding_pair_key unique (namespace_id, deployment_id),
    constraint praxis_bulk_namespace_binding_deployment_check check (btrim(deployment_id) <> '')
);

create table praxis_bulk.praxis_bulk_operation_control (
    namespace_id text not null references praxis_bulk.praxis_bulk_namespace_binding(namespace_id) on delete restrict,
    operation_id text not null,
    state text not null,
    generation bigint not null,
    descriptor_fingerprint text,
    structural_revision text,
    updated_at timestamptz not null,
    constraint praxis_bulk_operation_control_pkey primary key (namespace_id, operation_id),
    constraint praxis_bulk_operation_control_operation_check check (btrim(operation_id) <> ''),
    constraint praxis_bulk_operation_control_generation_check check (generation >= 0),
    constraint praxis_bulk_operation_control_state_check check (state in ('UNCOMPOSED', 'SUSPENDED', 'READY')),
    constraint praxis_bulk_operation_control_ready_check check (
        (state = 'READY' and descriptor_fingerprint is not null
             and descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$'
             and structural_revision is not null and btrim(structural_revision) <> '')
        or (state <> 'READY' and descriptor_fingerprint is null and structural_revision is null))
);

-- These rows are durable lock points. Counts are derived from allocations while
-- holding both bucket locks; counters would create an avoidable second ledger.
create table praxis_bulk.praxis_bulk_deployment_bucket (
    deployment_id text primary key,
    constraint praxis_bulk_deployment_bucket_id_check check (btrim(deployment_id) <> '')
);

create table praxis_bulk.praxis_bulk_subject_bucket (
    deployment_id text not null references praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) on delete restrict,
    subject_scope_digest_version integer not null,
    subject_scope_digest text not null,
    constraint praxis_bulk_subject_bucket_pkey primary key
        (deployment_id, subject_scope_digest_version, subject_scope_digest),
    constraint praxis_bulk_subject_bucket_version_check check (subject_scope_digest_version >= 1),
    constraint praxis_bulk_subject_bucket_digest_check check
        (subject_scope_digest ~ '^sha256:[0-9a-f]{64}$')
);

create table praxis_bulk.praxis_bulk_allocation (
    allocation_id uuid not null,
    namespace_id text not null,
    deployment_id text not null references praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) on delete restrict,
    subject_scope_digest_version integer not null,
    subject_scope_digest text not null,
    authorization_scope_digest_version integer not null,
    authorization_scope_digest text not null,
    kind text not null,
    proposal_id uuid,
    execution_id uuid,
    state text not null,
    created_at timestamptz not null,
    released_at timestamptz,
    release_reason text,
    constraint praxis_bulk_allocation_pkey primary key (allocation_id),
    constraint praxis_bulk_allocation_namespace_deployment_fkey foreign key (namespace_id, deployment_id)
        references praxis_bulk.praxis_bulk_namespace_binding(namespace_id, deployment_id) on delete restrict,
    constraint praxis_bulk_allocation_proposal_key unique (proposal_id),
    constraint praxis_bulk_allocation_execution_key unique (execution_id),
    constraint praxis_bulk_allocation_proposal_fkey foreign key (proposal_id)
        references praxis_bulk.praxis_bulk_proposal(proposal_id) on delete restrict,
    constraint praxis_bulk_allocation_execution_fkey foreign key (execution_id)
        references praxis_bulk.praxis_bulk_execution(execution_id) on delete restrict,
    constraint praxis_bulk_allocation_subject_bucket_fkey foreign key
        (deployment_id, subject_scope_digest_version, subject_scope_digest)
        references praxis_bulk.praxis_bulk_subject_bucket
        (deployment_id, subject_scope_digest_version, subject_scope_digest) on delete restrict,
    constraint praxis_bulk_allocation_scope_version_check check (authorization_scope_digest_version >= 1),
    constraint praxis_bulk_allocation_scope_digest_check check
        (authorization_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_allocation_shape_check check (
        (kind = 'PROPOSAL_PENDING' and proposal_id is not null and execution_id is null
            and state in ('PENDING', 'CONSUMED', 'RELEASED'))
        or (kind = 'EXECUTION_ACTIVE' and execution_id is not null and proposal_id is null
            and state in ('ACTIVE', 'RELEASED'))),
    constraint praxis_bulk_allocation_release_check check (
        (state in ('PENDING', 'ACTIVE', 'CONSUMED') and released_at is null and release_reason is null)
        or (kind = 'PROPOSAL_PENDING' and state = 'RELEASED'
            and release_reason is not null and release_reason = 'PROPOSAL_EXPIRED'
            and released_at is not null and released_at >= created_at)
        or (kind = 'EXECUTION_ACTIVE' and state = 'RELEASED'
            and release_reason is not null and release_reason = 'TERMINAL_RECONCILED'
            and released_at is not null
            and released_at >= created_at))
);

create index praxis_bulk_allocation_pending_deployment_idx on praxis_bulk.praxis_bulk_allocation(deployment_id)
    where kind = 'PROPOSAL_PENDING' and state = 'PENDING';
create index praxis_bulk_allocation_pending_subject_idx on praxis_bulk.praxis_bulk_allocation
    (deployment_id, subject_scope_digest_version, subject_scope_digest)
    where kind = 'PROPOSAL_PENDING' and state = 'PENDING';
create index praxis_bulk_allocation_active_deployment_idx on praxis_bulk.praxis_bulk_allocation(deployment_id)
    where kind = 'EXECUTION_ACTIVE' and state = 'ACTIVE';
create index praxis_bulk_execution_retention_idx on praxis_bulk.praxis_bulk_execution(terminal_at, execution_id)
    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED');
create index praxis_bulk_proposal_expiry_idx on praxis_bulk.praxis_bulk_proposal(expires_at, proposal_id);

-- A tombstone is the permanent, minimal replay and ID denial record. In particular
-- it does not contain the raw actor, target IDs, request, or domain outcome payload.
create table praxis_bulk.praxis_bulk_tombstone (
    namespace_id text not null references praxis_bulk.praxis_bulk_namespace_binding(namespace_id) on delete restrict,
    authorization_scope_digest_version integer not null,
    authorization_scope_digest text not null,
    resource_key text not null,
    operation_id text not null,
    idempotency_key_digest text not null,
    proposal_id uuid not null,
    execution_id uuid not null,
    terminal_status text not null,
    terminal_at timestamptz not null,
    purged_at timestamptz not null,
    constraint praxis_bulk_tombstone_pkey primary key
        (namespace_id, authorization_scope_digest_version, authorization_scope_digest,
         resource_key, operation_id, idempotency_key_digest),
    constraint praxis_bulk_tombstone_proposal_key unique (proposal_id),
    constraint praxis_bulk_tombstone_execution_key unique (execution_id),
    constraint praxis_bulk_tombstone_scope_version_check check (authorization_scope_digest_version >= 1),
    constraint praxis_bulk_tombstone_scope_digest_check check
        (authorization_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_tombstone_key_digest_check check
        (idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_tombstone_resource_check check (btrim(resource_key) <> ''),
    constraint praxis_bulk_tombstone_operation_check check (btrim(operation_id) <> ''),
    constraint praxis_bulk_tombstone_terminal_check check
        (terminal_status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')),
    constraint praxis_bulk_tombstone_time_check check (purged_at >= terminal_at)
);

-- A direct caller cannot delete protected evidence. The definer function switches
-- current_user to the dedicated owner; no session GUC is consulted.
create function praxis_bulk.guard_lifecycle_delete()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if current_user <> 'praxis_bulk_retention_owner' then
        raise exception 'protected bulk lifecycle row cannot be deleted directly' using errcode = '55000';
    end if;
    return old;
end;
$$;

create function praxis_bulk.guard_tombstone_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    raise exception 'bulk tombstone is immutable' using errcode = '55000';
end;
$$;

create trigger praxis_bulk_proposal_guard_delete before delete on praxis_bulk.praxis_bulk_proposal
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_evaluation_guard_delete before delete on praxis_bulk.praxis_bulk_evaluation
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_execution_guard_delete before delete on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_receipt_guard_delete before delete on praxis_bulk.praxis_bulk_item_receipt
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_admission_guard_delete before delete on praxis_bulk.praxis_bulk_admission
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_allocation_guard_delete before delete on praxis_bulk.praxis_bulk_allocation
    for each row execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_tombstone_guard_mutation before update or delete on praxis_bulk.praxis_bulk_tombstone
    for each row execute function praxis_bulk.guard_tombstone_mutation();

-- Lock-only UPDATE grants are required by PostgreSQL for SELECT ... FOR SHARE/UPDATE.
-- These durable identities are never mutable, including by the retention definer.
create function praxis_bulk.guard_bucket_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    raise exception 'bulk quota bucket identity is immutable' using errcode = '55000';
end;
$$;
create trigger praxis_bulk_deployment_bucket_guard_mutation
    before update or delete on praxis_bulk.praxis_bulk_deployment_bucket
    for each row execute function praxis_bulk.guard_bucket_mutation();
create trigger praxis_bulk_subject_bucket_guard_mutation
    before update or delete on praxis_bulk.praxis_bulk_subject_bucket
    for each row execute function praxis_bulk.guard_bucket_mutation();

-- V3/V4 already reject every receipt/admission mutation. Their DELETE branch
-- must admit only the retention definer; otherwise the new guard alone cannot
-- make an authorized purge possible. UPDATE remains forbidden for every role.
create or replace function praxis_bulk.reject_praxis_bulk_item_receipt_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if tg_op = 'DELETE' and current_user = 'praxis_bulk_retention_owner' then
        return old;
    end if;
    raise exception 'praxis_bulk.praxis_bulk_item_receipt is immutable' using errcode = '55000';
end;
$$;

create or replace function praxis_bulk.reject_praxis_bulk_admission_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if tg_op = 'DELETE' and current_user = 'praxis_bulk_retention_owner' then
        return old;
    end if;
    raise exception 'praxis_bulk.praxis_bulk_admission is immutable' using errcode = '55000';
end;
$$;

-- An allocation's identity and charged scope cannot be changed. Only the
-- PENDING -> CONSUMED/RELEASED or ACTIVE -> RELEASED transitions are legal.
create function praxis_bulk.protect_allocation_transition()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    if row(new.allocation_id, new.namespace_id, new.deployment_id,
           new.subject_scope_digest_version, new.subject_scope_digest,
           new.authorization_scope_digest_version, new.authorization_scope_digest,
           new.kind, new.proposal_id, new.execution_id, new.created_at)
       is distinct from
       row(old.allocation_id, old.namespace_id, old.deployment_id,
           old.subject_scope_digest_version, old.subject_scope_digest,
           old.authorization_scope_digest_version, old.authorization_scope_digest,
           old.kind, old.proposal_id, old.execution_id, old.created_at)
       or not ((old.state = 'PENDING' and new.state in ('CONSUMED', 'RELEASED'))
               or (old.state = 'ACTIVE' and new.state = 'RELEASED')) then
        raise exception 'invalid bulk allocation transition' using errcode = '55000';
    end if;
    if old.kind = 'PROPOSAL_PENDING' and new.state = 'CONSUMED'
       and not exists (select 1 from praxis_bulk.praxis_bulk_execution e
                       where e.proposal_id = old.proposal_id) then
        raise exception 'bulk proposal cannot be consumed without execution' using errcode = '55000';
    end if;
    if old.kind = 'PROPOSAL_PENDING' and new.state = 'RELEASED'
       and (exists (select 1 from praxis_bulk.praxis_bulk_execution e
                    where e.proposal_id = old.proposal_id)
            or not exists (select 1 from praxis_bulk.praxis_bulk_proposal p
                           where p.proposal_id = old.proposal_id
                             and p.expires_at <= clock_timestamp())) then
        raise exception 'bulk proposal cannot be released before expiry' using errcode = '55000';
    end if;
    if old.kind = 'EXECUTION_ACTIVE' and new.state = 'RELEASED' then
        select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
            where e.execution_id = old.execution_id;
        if not found or v_execution.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
           or v_execution.terminal_at is null
           or (v_execution.status = 'STOPPED' and
               (v_execution.terminal_reason_code is null or v_execution.active_attempt_id is not null))
           or (v_execution.status = 'COMPLETED' and exists
               (select 1 from praxis_bulk.praxis_bulk_admission a
                where a.execution_id = old.execution_id))
           or (v_execution.status = 'COMPLETED_WITH_ERRORS' and not exists
               (select 1 from praxis_bulk.praxis_bulk_admission a
                where a.execution_id = old.execution_id))
           or not praxis_bulk.terminal_evidence_complete(old.execution_id,
               case when v_execution.status = 'STOPPED'
                    then v_execution.next_ordinal else v_execution.target_count end) then
            raise exception 'bulk active allocation requires reconciled terminal evidence' using errcode = '55000';
        end if;
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_allocation_protect_transition before update on praxis_bulk.praxis_bulk_allocation
    for each row execute function praxis_bulk.protect_allocation_transition();

create function praxis_bulk.validate_allocation_binding()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_namespace text;
    v_status text;
    v_terminal_at timestamptz;
    v_expires_at timestamptz;
    v_next_ordinal integer;
    v_target_count integer;
    v_terminal_reason text;
    v_active_attempt_id uuid;
begin
    if new.proposal_id is not null then
        select p.namespace_id, p.expires_at into v_namespace, v_expires_at
            from praxis_bulk.praxis_bulk_proposal p
            where p.proposal_id = new.proposal_id;
        if new.state = 'PENDING' and exists
            (select 1 from praxis_bulk.praxis_bulk_execution e where e.proposal_id = new.proposal_id) then
            raise exception 'pending bulk proposal already has execution' using errcode = '55000';
        end if;
        if new.state = 'CONSUMED' and not exists
            (select 1 from praxis_bulk.praxis_bulk_execution e where e.proposal_id = new.proposal_id) then
            raise exception 'consumed bulk proposal requires execution' using errcode = '55000';
        end if;
        if new.state = 'RELEASED' and
           (v_expires_at is null or v_expires_at > clock_timestamp() or exists
            (select 1 from praxis_bulk.praxis_bulk_execution e where e.proposal_id = new.proposal_id)) then
            raise exception 'released bulk proposal requires expiry without execution' using errcode = '55000';
        end if;
    else
        select e.namespace_id, e.status, e.terminal_at, e.next_ordinal, e.target_count,
               e.terminal_reason_code, e.active_attempt_id
            into v_namespace, v_status, v_terminal_at, v_next_ordinal, v_target_count,
                 v_terminal_reason, v_active_attempt_id
            from praxis_bulk.praxis_bulk_execution e
            where e.execution_id = new.execution_id;
        if new.state = 'RELEASED' and
           (v_status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
            or v_terminal_at is null or not praxis_bulk.terminal_evidence_complete(
                new.execution_id, case when v_status = 'STOPPED' then v_next_ordinal else v_target_count end)
            or (v_status = 'STOPPED' and (v_terminal_reason is null or v_active_attempt_id is not null))
            or (v_status = 'COMPLETED' and exists
                (select 1 from praxis_bulk.praxis_bulk_admission a
                 where a.execution_id = new.execution_id))
            or (v_status = 'COMPLETED_WITH_ERRORS' and not exists
                (select 1 from praxis_bulk.praxis_bulk_admission a
                 where a.execution_id = new.execution_id))) then
            raise exception 'released bulk execution requires terminal evidence' using errcode = '55000';
        end if;
        if new.state = 'ACTIVE' and v_status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
            raise exception 'terminal bulk execution cannot consume active quota' using errcode = '55000';
        end if;
    end if;
    if v_namespace is null or v_namespace <> new.namespace_id then
        raise exception 'bulk allocation namespace does not match its owner' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_allocation_validate_binding before insert on praxis_bulk.praxis_bulk_allocation
    for each row execute function praxis_bulk.validate_allocation_binding();

create function praxis_bulk.protect_namespace_binding()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    raise exception 'bulk namespace binding is immutable' using errcode = '55000';
end;
$$;

create trigger praxis_bulk_namespace_binding_immutable before update or delete on praxis_bulk.praxis_bulk_namespace_binding
    for each row execute function praxis_bulk.protect_namespace_binding();

-- Control publication is CAS by generation. A suspended generation can be
-- activated only with a validated descriptor supplied by the composition layer.
create function praxis_bulk.protect_operation_control()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if new.namespace_id is distinct from old.namespace_id
       or new.operation_id is distinct from old.operation_id
       or new.generation <> old.generation + 1
       or new.updated_at < old.updated_at
       or not ((old.state in ('UNCOMPOSED', 'SUSPENDED') and new.state = 'READY')
               or (old.state = 'READY' and new.state = 'SUSPENDED')
               or (old.state = 'UNCOMPOSED' and new.state = 'SUSPENDED')
               or (old.state = 'SUSPENDED' and new.state = 'SUSPENDED')) then
        raise exception 'invalid bulk operation-control generation' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_operation_control_protect before update on praxis_bulk.praxis_bulk_operation_control
    for each row execute function praxis_bulk.protect_operation_control();

-- An older writer cannot bypass an incomplete V5 bootstrap. The application
-- also compares generation/fingerprint under this shared control lock and then
-- acquires the bucket locks in the documented order.
create function praxis_bulk.guard_new_bulk_admission()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_state text;
begin
    -- PL/pgSQL record fields must be accessed only inside the matching branch;
    -- boolean short-circuiting does not make NEW.status valid for proposal rows.
    if tg_table_name = 'praxis_bulk_execution' then
        if new.status <> 'RUNNING' or new.next_ordinal <> 0 or new.owner_epoch <> 1
           or new.active_attempt_id is not null or new.active_attempt_ordinal is not null
           or new.active_target_digest is not null or new.active_attempt_epoch is not null
           or new.active_unit_deadline_at is not null or new.terminal_at is not null
           or new.terminal_reason_code is not null then
            raise exception 'bulk execution insert must start in canonical RUNNING state' using errcode = '55000';
        end if;
    end if;
    select c.state into v_state from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id = new.namespace_id and c.operation_id = new.operation_id
        for share;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for admission' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_proposal_guard_admission before insert on praxis_bulk.praxis_bulk_proposal
    for each row execute function praxis_bulk.guard_new_bulk_admission();
create trigger praxis_bulk_execution_guard_admission before insert on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.guard_new_bulk_admission();

-- Evaluation is a separate durable write, so an operation suspended after a
-- proposal was created must not accept a new evaluation for that proposal.
create function praxis_bulk.guard_new_bulk_evaluation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
    v_namespace text;
    v_operation text;
    v_state text;
begin
    -- Read immutable identity without a row lock first, then acquire locks in
    -- control -> proposal order to match reserve/expiry/purge paths.
    select p.namespace_id, p.operation_id into v_namespace, v_operation
        from praxis_bulk.praxis_bulk_proposal p
        where p.proposal_id = new.proposal_id;
    if not found then
        raise exception 'bulk evaluation proposal does not exist' using errcode = '55000';
    end if;
    select c.state into v_state from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id = v_namespace and c.operation_id = v_operation for share;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for evaluation' using errcode = '55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_proposal p
        where p.proposal_id = new.proposal_id
          and p.namespace_id = v_namespace and p.operation_id = v_operation for share;
    if not found then
        raise exception 'bulk evaluation proposal binding changed' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_evaluation_guard_admission before insert on praxis_bulk.praxis_bulk_evaluation
    for each row execute function praxis_bulk.guard_new_bulk_evaluation();

-- The union is disjoint by ordinal and covers exactly the requested range.
-- STOPPED covers only its committed prefix. This function is also used by
-- retention so a historical terminal row cannot be purged on status alone.
create function praxis_bulk.terminal_evidence_complete(p_execution_id uuid, p_required_count integer)
returns boolean language sql stable set search_path = pg_catalog, pg_temp as $$
    select exists (select 1 from praxis_bulk.praxis_bulk_execution e
                   where e.execution_id = p_execution_id)
       and p_required_count >= 0
       and not exists (
           select 1 from praxis_bulk.praxis_bulk_item_receipt r
           join praxis_bulk.praxis_bulk_admission a on a.attempt_id = r.attempt_id
           where r.execution_id = p_execution_id or a.execution_id = p_execution_id)
       and count(*) = p_required_count
       and count(distinct evidence.unit_ordinal) = p_required_count
       and count(distinct evidence.target_digest) = p_required_count
       and count(distinct evidence.attempt_id) = p_required_count
       and coalesce(max(evidence.owner_epoch), 0) <=
           (select e.owner_epoch from praxis_bulk.praxis_bulk_execution e
            where e.execution_id = p_execution_id)
       and (p_required_count = 0 or
            (min(evidence.unit_ordinal) = 0 and max(evidence.unit_ordinal) = p_required_count - 1))
    from (
        select r.unit_ordinal, r.target_digest, r.attempt_id, r.owner_epoch
        from praxis_bulk.praxis_bulk_item_receipt r where r.execution_id = p_execution_id
        union all
        select a.unit_ordinal, a.target_digest, a.attempt_id, a.owner_epoch
        from praxis_bulk.praxis_bulk_admission a where a.execution_id = p_execution_id
    ) evidence;
$$;

create function praxis_bulk.guard_terminal_execution()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_admission_count bigint;
begin
    if old.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and new is distinct from old then
        raise exception 'bulk terminal execution is fenced and immutable' using errcode = '55000';
    end if;
    if old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
        -- Retention starts at the database-observed transition, never at a caller
        -- supplied timestamp that could make a fresh result immediately purgeable.
        new.terminal_at := clock_timestamp();
    end if;
    if new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and (old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
            or new.status is distinct from old.status) then
        if new.active_attempt_id is not null or new.owner_epoch < 1
           or new.owner_epoch not in (old.owner_epoch, old.owner_epoch + 1)
           or new.next_ordinal < old.next_ordinal
           or new.terminal_at is null
           or (new.status = 'STOPPED' and
               (new.terminal_reason_code is null or
                not praxis_bulk.terminal_evidence_complete(new.execution_id, new.next_ordinal)))
           or (new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') and
               (new.next_ordinal <> new.target_count or
                not praxis_bulk.terminal_evidence_complete(new.execution_id, new.target_count))) then
            raise exception 'bulk execution lacks terminal evidence or fencing' using errcode = '55000';
        end if;
        if new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') then
            select count(*) into v_admission_count
            from praxis_bulk.praxis_bulk_admission a where a.execution_id = new.execution_id;
            if (new.status = 'COMPLETED' and v_admission_count <> 0)
               or (new.status = 'COMPLETED_WITH_ERRORS' and v_admission_count = 0) then
                raise exception 'bulk execution terminal status does not match unit evidence' using errcode = '55000';
            end if;
        end if;
    end if;
    return new;
end;
$$;

create trigger praxis_bulk_execution_guard_terminal before update on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.guard_terminal_execution();

-- Releasing the active slot is part of the same database transaction as the
-- evidence-checked terminal transition. The decrease can race with admission
-- without violating a limit: a concurrent admission may conservatively see
-- the previous ACTIVE row and reject, but can never undercount it.
create function praxis_bulk.release_active_allocation_on_terminal()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_released bigint;
begin
    if old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
        update praxis_bulk.praxis_bulk_allocation
           set state='RELEASED', released_at=clock_timestamp(), release_reason='TERMINAL_RECONCILED'
         where execution_id=new.execution_id and kind='EXECUTION_ACTIVE' and state='ACTIVE';
        get diagnostics v_released = row_count;
        if v_released <> 1 then
            raise exception 'terminal bulk execution has no unique active quota allocation' using errcode = '55000';
        end if;
    end if;
    return null;
end;
$$;
create trigger praxis_bulk_execution_release_active_allocation
    after update on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.release_active_allocation_on_terminal();

create function praxis_bulk.guard_terminal_evidence_insert()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
        where e.execution_id = new.execution_id for update;
    if not found or v_execution.status <> 'UNIT_IN_FLIGHT'
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

create trigger praxis_bulk_receipt_guard_terminal before insert on praxis_bulk.praxis_bulk_item_receipt
    for each row execute function praxis_bulk.guard_terminal_evidence_insert();
create trigger praxis_bulk_admission_guard_terminal before insert on praxis_bulk.praxis_bulk_admission
    for each row execute function praxis_bulk.guard_terminal_evidence_insert();

-- The migration account must have CREATEROLE/ADMIN for these dedicated roles.
-- Ownership membership is granted only within Flyway's transaction and removed
-- before commit, so runtime can never SET ROLE to the NOLOGIN definer.
do $$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_retention_owner') then
        create role praxis_bulk_retention_owner nologin noinherit;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_retention_executor') then
        create role praxis_bulk_retention_executor nologin noinherit;
    end if;
    if exists (select 1 from pg_catalog.pg_roles where rolname in
        ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor')
        and (rolcanlogin or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
       or exists (select 1 from pg_catalog.pg_roles
           where rolname = 'praxis_bulk_retention_owner' and rolinherit)
       or exists (select 1 from pg_catalog.pg_roles
           where rolname = 'praxis_bulk_retention_executor' and rolinherit)
       or exists (select 1 from pg_catalog.pg_auth_members m
           join pg_catalog.pg_roles r on r.oid = m.roleid
           where r.rolname = 'praxis_bulk_retention_owner')
       or exists (select 1 from pg_catalog.pg_auth_members m
           join pg_catalog.pg_roles r on r.oid = m.member
           where r.rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor')) then
        raise exception 'bulk retention roles must be NOLOGIN and owner must have no members';
    end if;
end;
$$;

revoke all on schema praxis_bulk from public;
grant usage on schema praxis_bulk to praxis_bulk_retention_owner, praxis_bulk_retention_executor;
grant create on schema praxis_bulk to praxis_bulk_retention_owner;
grant update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to praxis_bulk_retention_owner;
grant update (state) on praxis_bulk.praxis_bulk_operation_control to praxis_bulk_retention_owner;
grant update (deployment_id) on praxis_bulk.praxis_bulk_deployment_bucket to praxis_bulk_retention_owner;
grant update (deployment_id) on praxis_bulk.praxis_bulk_subject_bucket to praxis_bulk_retention_owner;
grant update (proposal_id) on praxis_bulk.praxis_bulk_proposal to praxis_bulk_retention_owner;
grant update (execution_id) on praxis_bulk.praxis_bulk_execution to praxis_bulk_retention_owner;
grant select on praxis_bulk.praxis_bulk_namespace_binding,
    praxis_bulk.praxis_bulk_operation_control,
    praxis_bulk.praxis_bulk_deployment_bucket,
    praxis_bulk.praxis_bulk_subject_bucket,
    praxis_bulk.praxis_bulk_proposal,
    praxis_bulk.praxis_bulk_evaluation,
    praxis_bulk.praxis_bulk_execution,
    praxis_bulk.praxis_bulk_item_receipt,
    praxis_bulk.praxis_bulk_admission,
    praxis_bulk.praxis_bulk_allocation,
    praxis_bulk.praxis_bulk_tombstone to praxis_bulk_retention_owner;
grant delete on praxis_bulk.praxis_bulk_proposal,
    praxis_bulk.praxis_bulk_evaluation,
    praxis_bulk.praxis_bulk_execution,
    praxis_bulk.praxis_bulk_item_receipt,
    praxis_bulk.praxis_bulk_admission,
    praxis_bulk.praxis_bulk_allocation to praxis_bulk_retention_owner;
grant update (state, released_at, release_reason) on praxis_bulk.praxis_bulk_allocation
    to praxis_bulk_retention_owner;
grant insert on praxis_bulk.praxis_bulk_tombstone to praxis_bulk_retention_owner;

-- The owner of the evidence-checking triggers has SELECT and helper EXECUTE,
-- while the runtime role needs no direct EXECUTE on the UUID-check helper.
-- The physical catalog validator must require owner, SECURITY DEFINER, fixed
-- search_path and absence of PUBLIC EXECUTE on these trigger functions.
-- One execution per invocation. The caller must use one invocation per short
-- transaction; the function never loops over a batch. Candidate reads happen
-- before locks, then every predicate is rechecked under the canonical order.
create function praxis_bulk.purge_terminal_execution(p_execution_id uuid)
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
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = v_execution.proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = v_execution.proposal_id;
    return true;
end;
$$;

-- An unconsumed expired proposal has no execution/tombstone. Its subject and
-- deployment slots are released by deleting the pending allocation in this
-- same transaction; no mutable counter or synthetic execution is produced.
create function praxis_bulk.expire_unconsumed_proposal(p_proposal_id uuid)
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
    delete from praxis_bulk.praxis_bulk_evaluation where proposal_id = p_proposal_id;
    delete from praxis_bulk.praxis_bulk_proposal where proposal_id = p_proposal_id;
    return true;
end;
$$;

do $$
begin
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I', current_user);
end;
$$;
alter function praxis_bulk.purge_terminal_execution(uuid) owner to praxis_bulk_retention_owner;
alter function praxis_bulk.expire_unconsumed_proposal(uuid) owner to praxis_bulk_retention_owner;
alter function praxis_bulk.protect_allocation_transition() owner to praxis_bulk_retention_owner;
alter function praxis_bulk.validate_allocation_binding() owner to praxis_bulk_retention_owner;
alter function praxis_bulk.guard_terminal_execution() owner to praxis_bulk_retention_owner;
alter function praxis_bulk.release_active_allocation_on_terminal() owner to praxis_bulk_retention_owner;
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
revoke create on schema praxis_bulk from praxis_bulk_retention_owner;
revoke all on function praxis_bulk.guard_lifecycle_delete() from public;
revoke all on function praxis_bulk.guard_tombstone_mutation() from public;
revoke all on function praxis_bulk.protect_allocation_transition() from public;
revoke all on function praxis_bulk.guard_bucket_mutation() from public;
revoke all on function praxis_bulk.validate_allocation_binding() from public;
revoke all on function praxis_bulk.protect_namespace_binding() from public;
revoke all on function praxis_bulk.protect_operation_control() from public;
revoke all on function praxis_bulk.guard_new_bulk_admission() from public;
revoke all on function praxis_bulk.guard_new_bulk_evaluation() from public;
revoke all on function praxis_bulk.terminal_evidence_complete(uuid, integer) from public;
revoke all on function praxis_bulk.guard_terminal_execution() from public;
revoke all on function praxis_bulk.release_active_allocation_on_terminal() from public;
revoke all on function praxis_bulk.guard_terminal_evidence_insert() from public;
revoke all on function praxis_bulk.purge_terminal_execution(uuid) from public;
revoke all on function praxis_bulk.expire_unconsumed_proposal(uuid) from public;
grant execute on function praxis_bulk.terminal_evidence_complete(uuid, integer)
    to praxis_bulk_retention_owner;
grant execute on function praxis_bulk.purge_terminal_execution(uuid) to praxis_bulk_retention_executor;
grant execute on function praxis_bulk.expire_unconsumed_proposal(uuid) to praxis_bulk_retention_executor;
