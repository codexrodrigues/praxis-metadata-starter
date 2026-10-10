-- Non-rolling beta cutover: old writers must be drained before this migration.
-- R2 removes the six-argument CAS; no legacy writer may republish an unbound READY.
do $$ begin
    if (select count(*) from pg_catalog.pg_roles
        where rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner')
          and not (rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls)) <> 2
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid
                  where r.rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner'))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.member
                  where r.rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner'))
       or pg_catalog.has_schema_privilege('praxis_bulk_control_owner','praxis_bulk','CREATE')
       or pg_catalog.has_schema_privilege('praxis_bulk_retention_owner','praxis_bulk','CREATE') then
        raise exception 'bulk definer-owner topology is unsafe before publication-fence migration';
    end if;
end $$;

-- DDL takes ACCESS EXCLUSIVE on controls: retain namespace -> global locks first.
-- This is a drained, non-rolling cutover, never a concurrent old-writer upgrade.
do $$ begin
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding order by namespace_id for share;
    perform 1 from praxis_bulk.praxis_bulk_openapi_publication order by deployment_id for update;
end $$;

alter table praxis_bulk.praxis_bulk_operation_control
    add column publication_generation bigint,
    add column publication_document_digest text;

-- One transaction invalidates every legacy READY and published global identity.
-- Acquire all namespaces -> globals -> controls before preflight or mutation.
do $$ begin
    perform 1 from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id for update;
    if exists (select 1 from praxis_bulk.praxis_bulk_openapi_publication
               where state='PUBLISHED' and generation=9223372036854775807)
       or exists (select 1 from praxis_bulk.praxis_bulk_operation_control
                  where state='READY' and generation=9223372036854775807) then
        raise exception 'bulk publication cutover generation cannot advance' using errcode='22003';
    end if;
    update praxis_bulk.praxis_bulk_operation_control
       set state='SUSPENDED',generation=generation+1,descriptor_fingerprint=null,
           structural_revision=null,publication_generation=null,publication_document_digest=null,
           updated_at=greatest(clock_timestamp(),updated_at)
     where state='READY';
    update praxis_bulk.praxis_bulk_openapi_publication
       set state='SUSPENDED',generation=generation+1,document_digest=null,
           updated_at=greatest(clock_timestamp(),updated_at)
     where state='PUBLISHED';
end $$;

alter table praxis_bulk.praxis_bulk_operation_control
    add constraint praxis_bulk_operation_control_publication_check check (
        (state='READY' and publication_generation is not null and publication_generation>=1
             and publication_document_digest is not null
             and publication_document_digest ~ '^sha256:[0-9a-f]{64}$')
        or (state<>'READY' and publication_generation is null and publication_document_digest is null));
grant update (publication_generation,publication_document_digest)
    on praxis_bulk.praxis_bulk_operation_control to praxis_bulk_control_owner;

-- Replacing definer-owned functions requires only temporary, bounded memberships.
do $$ begin
    execute pg_catalog.format('grant praxis_bulk_control_owner to %I',current_user);
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I',current_user);
end $$;
grant create on schema praxis_bulk to praxis_bulk_control_owner;

create or replace function praxis_bulk.lock_operation_control(p_namespace_id text,p_operation_id text)
returns table(state text,generation bigint,descriptor_fingerprint text,structural_revision text)
language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
declare
    v_deployment text;
    v_publication record;
begin
    if p_namespace_id is null or btrim(p_namespace_id)='' or p_operation_id is null or btrim(p_operation_id)='' then
        raise exception 'bulk operation-control identity is invalid' using errcode='22023';
    end if;
    select b.deployment_id into v_deployment from praxis_bulk.praxis_bulk_namespace_binding b
        where b.namespace_id=p_namespace_id;
    if not found then return; end if;
    select p.* into v_publication from praxis_bulk.lock_openapi_publication(p_namespace_id,v_deployment) p;
    -- Locks remain available for receipt replay and cleanup when publication is closed.
    -- Only READY is filtered: a stale/forged publication tuple never grants admission.
    return query select c.state,c.generation,c.descriptor_fingerprint,c.structural_revision
      from praxis_bulk.praxis_bulk_operation_control c
     where c.namespace_id=p_namespace_id and c.operation_id=p_operation_id
       and (c.state<>'READY' or (v_publication.state='PUBLISHED'
            and c.publication_generation=v_publication.generation
            and c.publication_document_digest=v_publication.document_digest))
     for share;
end;
$$;

-- No six-argument compatibility overload survives the beta cutover.
drop function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text);
create function praxis_bulk.transition_operation_control(
    p_namespace_id text,p_operation_id text,p_expected_generation bigint,p_target_state text,
    p_descriptor_fingerprint text,p_structural_revision text,
    p_expected_publication_generation bigint,p_expected_publication_digest text)
returns table(applied boolean,generation bigint)
language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
declare
    v_control praxis_bulk.praxis_bulk_operation_control%rowtype;
    v_publication record;
    v_deployment text;
begin
    if p_namespace_id is null or btrim(p_namespace_id)='' or p_operation_id is null or btrim(p_operation_id)=''
       or p_expected_generation is null or p_expected_generation<0 then
        raise exception 'bulk operation-control identity or generation is invalid' using errcode='22023';
    end if;
    if p_expected_generation=9223372036854775807 then
        raise exception 'bulk operation generation cannot advance' using errcode='22003';
    end if;
    if p_target_state='READY' then
        if p_descriptor_fingerprint is null or p_descriptor_fingerprint !~ '^sha256:[0-9a-f]{64}$'
           or p_structural_revision is null or btrim(p_structural_revision)='' or length(p_structural_revision)>200
           or p_expected_publication_generation is null or p_expected_publication_generation<1
           or p_expected_publication_digest is null or p_expected_publication_digest !~ '^sha256:[0-9a-f]{64}$' then
            raise exception 'READY requires descriptor and publication tuples' using errcode='22023';
        end if;
    elsif p_target_state='SUSPENDED' then
        if p_descriptor_fingerprint is not null or p_structural_revision is not null
           or p_expected_publication_generation is not null or p_expected_publication_digest is not null then
            raise exception 'SUSPENDED must clear descriptor and publication tuples' using errcode='22023';
        end if;
    else
        raise exception 'bulk operation-control target state is invalid' using errcode='22023';
    end if;
    select b.deployment_id into v_deployment from praxis_bulk.praxis_bulk_namespace_binding b
        where b.namespace_id=p_namespace_id;
    if not found then raise exception 'bulk namespace binding is unavailable' using errcode='55000'; end if;
    select p.* into v_publication from praxis_bulk.lock_openapi_publication(p_namespace_id,v_deployment) p;
    select c.* into v_control from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id=p_namespace_id and c.operation_id=p_operation_id for update;
    if not found then raise exception 'bulk operation-control row is missing' using errcode='55000'; end if;
    if v_control.generation<>p_expected_generation then
        return query select false,v_control.generation; return;
    end if;
    if p_target_state='READY' and (v_publication.state<>'PUBLISHED'
       or v_publication.generation is distinct from p_expected_publication_generation
       or v_publication.document_digest is distinct from p_expected_publication_digest) then
        raise exception 'bulk OpenAPI publication tuple is not current' using errcode='55000';
    end if;
    update praxis_bulk.praxis_bulk_operation_control c
       set state=p_target_state,generation=v_control.generation+1,
           descriptor_fingerprint=p_descriptor_fingerprint,structural_revision=p_structural_revision,
           publication_generation=p_expected_publication_generation,
           publication_document_digest=p_expected_publication_digest,
           updated_at=greatest(clock_timestamp(),v_control.updated_at)
     where c.namespace_id=p_namespace_id and c.operation_id=p_operation_id;
    return query select true,v_control.generation+1;
end;
$$;
revoke all on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) from public;
alter function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text)
    owner to praxis_bulk_control_owner;
-- Host grants EXECUTE on the new eight-argument CAS explicitly; no ACL repair.

create or replace function praxis_bulk.guard_new_bulk_admission()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_state text;
begin
    if tg_table_name = 'praxis_bulk_execution' then
        if new.status <> 'RUNNING' or new.next_ordinal <> 0 or new.owner_epoch <> 1
           or new.active_attempt_id is not null or new.active_attempt_ordinal is not null
           or new.active_target_digest is not null or new.active_attempt_epoch is not null
           or new.active_unit_deadline_at is not null or new.terminal_at is not null
           or new.terminal_reason_code is not null then
            raise exception 'bulk execution insert must start in canonical RUNNING state' using errcode = '55000';
        end if;
    end if;
    select c.state into v_state from praxis_bulk.lock_operation_control(new.namespace_id,new.operation_id) c;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for admission' using errcode = '55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.guard_new_bulk_evaluation()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_namespace text;
    v_operation text;
    v_state text;
begin
    select p.namespace_id, p.operation_id into v_namespace, v_operation
        from praxis_bulk.praxis_bulk_proposal p
        where p.proposal_id = new.proposal_id;
    if not found then
        raise exception 'bulk evaluation proposal does not exist' using errcode = '55000';
    end if;
    select c.state into v_state from praxis_bulk.lock_operation_control(v_namespace,v_operation) c;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for evaluation' using errcode = '55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.transition_openapi_publication(
    p_namespace_id text, p_deployment_id text, p_expected_generation bigint,
    p_target_state text, p_document_digest text)
returns table(applied boolean, generation bigint)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_publication praxis_bulk.praxis_bulk_openapi_publication%rowtype;
    v_operation record;
begin
    if p_namespace_id is null or p_namespace_id = ''
       or p_namespace_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_deployment_id is null or p_deployment_id = ''
       or p_deployment_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_expected_generation is null or p_expected_generation < 0 then
        raise exception 'bulk OpenAPI identity or generation is invalid' using errcode = '22023';
    end if;
    if p_expected_generation = 9223372036854775807 then
        raise exception 'bulk OpenAPI generation cannot advance' using errcode = '22003';
    end if;
    if p_target_state = 'PUBLISHED' then
        if p_document_digest is null or p_document_digest !~ '^sha256:[0-9a-f]{64}$' then
            raise exception 'PUBLISHED requires a canonical document digest' using errcode = '22023';
        end if;
    elsif p_target_state = 'SUSPENDED' then
        if p_document_digest is not null then
            raise exception 'SUSPENDED must clear the document digest' using errcode = '22023';
        end if;
    else
        raise exception 'bulk OpenAPI target state is invalid' using errcode = '22023';
    end if;

    -- Namespace SHARE locks do not protect future inserts. R2 must make every READY
    -- transition acquire this global row and validate its generation; new controls
    -- remain deny-only at bootstrap. R1 alone does not close that admission fence.
    -- Global order: namespace bindings SHARE -> publication UPDATE -> operation
    -- controls ordered by namespace_id, operation_id. This function never acquires quota buckets.
    if not exists (select 1 from praxis_bulk.praxis_bulk_namespace_binding b
                   where b.namespace_id = p_namespace_id and b.deployment_id = p_deployment_id) then
        raise exception 'bulk OpenAPI deployment binding is unavailable' using errcode = '55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.deployment_id = p_deployment_id order by b.namespace_id for share;
    select c.* into v_publication from praxis_bulk.praxis_bulk_openapi_publication c
        where c.deployment_id = p_deployment_id for update;
    if not found then
        raise exception 'bulk OpenAPI publication row is missing' using errcode = '55000';
    end if;
    if v_publication.generation <> p_expected_generation then
        return query select false, v_publication.generation;
        return;
    end if;
    if p_target_state = 'PUBLISHED' and v_publication.state <> 'SUSPENDED' then
        raise exception 'bulk OpenAPI publication must first be suspended' using errcode = '55000';
    end if;
    if p_target_state = 'SUSPENDED' then
        perform 1 from praxis_bulk.praxis_bulk_operation_control c
            where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                     where b.deployment_id = p_deployment_id)
            order by c.namespace_id, c.operation_id for update;
        -- Preflight every affected generation before changing any operation row.
        if exists (select 1 from praxis_bulk.praxis_bulk_operation_control c
                   where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                             where b.deployment_id = p_deployment_id) and c.state = 'READY'
                     and c.generation = 9223372036854775807) then
            raise exception 'bulk operation generation cannot advance' using errcode = '22003';
        end if;
        for v_operation in select c.namespace_id, c.operation_id from praxis_bulk.praxis_bulk_operation_control c
                           where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                             where b.deployment_id = p_deployment_id) and c.state = 'READY' order by c.namespace_id, c.operation_id loop
            update praxis_bulk.praxis_bulk_operation_control c
                set state = 'SUSPENDED', generation = c.generation + 1,
                    descriptor_fingerprint = null, structural_revision = null,
                    publication_generation = null, publication_document_digest = null,
                    updated_at = greatest(clock_timestamp(), c.updated_at)
                where c.namespace_id = v_operation.namespace_id and c.operation_id = v_operation.operation_id;
        end loop;
    end if;
    update praxis_bulk.praxis_bulk_openapi_publication c
        set state = p_target_state, generation = v_publication.generation + 1,
            document_digest = p_document_digest, updated_at = greatest(clock_timestamp(), c.updated_at)
        where c.deployment_id = p_deployment_id;
    return query select true, v_publication.generation + 1;
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
    perform 1 from praxis_bulk.lock_operation_control(
        (select namespace_id from praxis_bulk.praxis_bulk_execution where execution_id=p_execution_id),
        (select operation_id from praxis_bulk.praxis_bulk_execution where execution_id=p_execution_id));
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
    perform 1 from praxis_bulk.lock_operation_control(
        (select namespace_id from praxis_bulk.praxis_bulk_proposal where proposal_id=p_proposal_id),
        (select operation_id from praxis_bulk.praxis_bulk_proposal where proposal_id=p_proposal_id));
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

-- Retention uses the same namespace -> global -> operation lock, without requiring READY.
grant execute on function praxis_bulk.lock_operation_control(text,text) to praxis_bulk_retention_owner;
revoke select,update (state) on praxis_bulk.praxis_bulk_operation_control from praxis_bulk_retention_owner;
revoke create on schema praxis_bulk from praxis_bulk_control_owner;
do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_control_owner from %I',current_user);
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I',current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid
               where r.rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner'))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.member
                  where r.rolname in ('praxis_bulk_control_owner','praxis_bulk_retention_owner')) then
        raise exception 'bulk definer-owner membership was not fully revoked';
    end if;
end $$;
