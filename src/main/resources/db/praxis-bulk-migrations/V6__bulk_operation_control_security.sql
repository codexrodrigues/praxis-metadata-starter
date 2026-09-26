-- V5 is immutable once present in main/deployed catalogs. V6 moves control-row
-- locks and readiness CAS behind narrowly owned SECURITY DEFINER functions.
do $$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner') then
        create role praxis_bulk_control_owner nologin noinherit;
    end if;
    if exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner'
               and (rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole
                    or rolreplication or rolbypassrls))
       or exists (select 1 from pg_catalog.pg_auth_members m
                  join pg_catalog.pg_roles r on r.oid = m.roleid
                  where r.rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_auth_members m
                  join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner') then
        raise exception 'bulk control owner must be NOLOGIN/NOINHERIT without role memberships';
    end if;
end;
$$;

revoke all on schema praxis_bulk from public;
grant usage on schema praxis_bulk to praxis_bulk_control_owner;
grant select on praxis_bulk.praxis_bulk_operation_control to praxis_bulk_control_owner;
grant update (state, generation, descriptor_fingerprint, structural_revision, updated_at)
    on praxis_bulk.praxis_bulk_operation_control to praxis_bulk_control_owner;
grant select (proposal_id, namespace_id, operation_id)
    on praxis_bulk.praxis_bulk_proposal to praxis_bulk_control_owner;

create function praxis_bulk.lock_operation_control(p_namespace_id text, p_operation_id text)
returns table(state text, generation bigint, descriptor_fingerprint text, structural_revision text)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
    if p_namespace_id is null or btrim(p_namespace_id) = ''
       or p_operation_id is null or btrim(p_operation_id) = '' then
        raise exception 'bulk operation-control identity is invalid' using errcode = '22023';
    end if;
    return query
        select c.state, c.generation, c.descriptor_fingerprint, c.structural_revision
          from praxis_bulk.praxis_bulk_operation_control c
         where c.namespace_id = p_namespace_id and c.operation_id = p_operation_id
         for share;
end;
$$;

-- The caller is an explicitly configured control-plane grantee. It receives
-- EXECUTE only; this CAS runs under a dedicated NOLOGIN table owner.
create function praxis_bulk.transition_operation_control(
    p_namespace_id text, p_operation_id text, p_expected_generation bigint,
    p_target_state text, p_descriptor_fingerprint text, p_structural_revision text)
returns table(applied boolean, generation bigint)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_control praxis_bulk.praxis_bulk_operation_control%rowtype;
begin
    if p_namespace_id is null or btrim(p_namespace_id) = ''
       or p_operation_id is null or btrim(p_operation_id) = ''
       or p_expected_generation is null or p_expected_generation < 0 then
        raise exception 'bulk operation-control identity or generation is invalid' using errcode = '22023';
    end if;
    if p_target_state = 'READY' then
        if p_descriptor_fingerprint is null
           or p_descriptor_fingerprint !~ '^sha256:[0-9a-f]{64}$'
           or p_structural_revision is null or btrim(p_structural_revision) = '' then
            raise exception 'READY requires a complete structural descriptor' using errcode = '22023';
        end if;
    elsif p_target_state = 'SUSPENDED' then
        if p_descriptor_fingerprint is not null or p_structural_revision is not null then
            raise exception 'SUSPENDED must clear the structural descriptor' using errcode = '22023';
        end if;
    else
        raise exception 'bulk operation-control target state is invalid' using errcode = '22023';
    end if;

    select c.* into v_control from praxis_bulk.praxis_bulk_operation_control c
     where c.namespace_id = p_namespace_id and c.operation_id = p_operation_id
     for update;
    if not found then
        raise exception 'bulk operation-control row is missing' using errcode = '55000';
    end if;
    if v_control.generation <> p_expected_generation then
        return query select false, v_control.generation;
        return;
    end if;
    update praxis_bulk.praxis_bulk_operation_control c
       set state = p_target_state,
           generation = v_control.generation + 1,
           descriptor_fingerprint = p_descriptor_fingerprint,
           structural_revision = p_structural_revision,
           updated_at = greatest(clock_timestamp(), v_control.updated_at)
     where c.namespace_id = p_namespace_id and c.operation_id = p_operation_id;
    return query select true, v_control.generation + 1;
end;
$$;

-- Existing admission triggers also need row locks. Making the guards definer
-- functions lets runtime insert without receiving table UPDATE privileges.
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
    select c.state into v_state from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id = new.namespace_id and c.operation_id = new.operation_id
        for share;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for admission' using errcode = '55000';
    end if;
    return new;
end;
$$;

-- Proposal identity is immutable, so the trigger can read its locator before
-- taking the operation-control lock. This avoids requiring UPDATE grants merely
-- to row-lock the proposal and preserves the control -> proposal lock order.
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
    select c.state into v_state from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id = v_namespace and c.operation_id = v_operation for share;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for evaluation' using errcode = '55000';
    end if;
    return new;
end;
$$;

-- PostgreSQL requires owner membership only while assigning function ownership.
do $$
begin
    execute pg_catalog.format('grant praxis_bulk_control_owner to %I', current_user);
end;
$$;
alter function praxis_bulk.lock_operation_control(text, text) owner to praxis_bulk_control_owner;
alter function praxis_bulk.transition_operation_control(text, text, bigint, text, text, text)
    owner to praxis_bulk_control_owner;
alter function praxis_bulk.guard_new_bulk_admission() owner to praxis_bulk_control_owner;
alter function praxis_bulk.guard_new_bulk_evaluation() owner to praxis_bulk_control_owner;
do $$
begin
    execute pg_catalog.format('revoke praxis_bulk_control_owner from %I', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m
               join pg_catalog.pg_roles r on r.oid = m.roleid
               where r.rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_auth_members m
                  join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner') then
        raise exception 'bulk control-owner membership was not fully revoked';
    end if;
end;
$$;
revoke create on schema praxis_bulk from praxis_bulk_control_owner;
revoke all on function praxis_bulk.lock_operation_control(text, text) from public;
revoke all on function praxis_bulk.transition_operation_control(text, text, bigint, text, text, text) from public;
revoke all on function praxis_bulk.guard_new_bulk_admission() from public;
revoke all on function praxis_bulk.guard_new_bulk_evaluation() from public;
