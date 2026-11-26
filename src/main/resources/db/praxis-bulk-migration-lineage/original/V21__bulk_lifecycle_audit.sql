-- Governed lifecycle persistent audit ledger and atomic deployment-wide suspension CAS.
-- Ownership stays restricted to praxis_bulk_control_owner; host roles only execute bounded SECURITY DEFINER functions.
do $$ begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner'
                  and (rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.roleid
                  where r.rolname = 'praxis_bulk_control_owner'
               and not (m.admin_option and not m.inherit_option and not m.set_option
                  and m.member=(select nspowner from pg_catalog.pg_namespace where nspname='praxis_bulk')
                  and exists(select 1 from pg_catalog.pg_roles bootstrap
                             where bootstrap.oid=m.grantor and bootstrap.rolsuper)
                  and (select count(*) from pg_catalog.pg_auth_members same_actor
                       where same_actor.roleid=m.roleid and same_actor.member=m.member
                         and same_actor.admin_option and not same_actor.inherit_option and not same_actor.set_option)=1))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner'
               and not (m.admin_option and not m.inherit_option and not m.set_option
                  and m.member=(select nspowner from pg_catalog.pg_namespace where nspname='praxis_bulk')
                  and exists(select 1 from pg_catalog.pg_roles bootstrap
                             where bootstrap.oid=m.grantor and bootstrap.rolsuper)
                  and (select count(*) from pg_catalog.pg_auth_members same_actor
                       where same_actor.roleid=m.roleid and same_actor.member=m.member
                         and same_actor.admin_option and not same_actor.inherit_option and not same_actor.set_option)=1))
       or pg_catalog.has_schema_privilege('praxis_bulk_control_owner', 'praxis_bulk', 'CREATE') then
        raise exception 'bulk control-owner topology is unsafe before lifecycle audit migration';
    end if;
end $$;

create table praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap (
    bootstrap_version smallint constraint praxis_bulk_lifecycle_audit_bootstrap_pkey primary key
        constraint lifecycle_audit_bootstrap_version_check check (bootstrap_version = 21),
    phase text not null constraint praxis_bulk_lifecycle_audit_bootstrap_phase_check
        check (phase in ('PENDING', 'COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap (bootstrap_version, phase)
values (21, 'PENDING');
revoke all on table praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap from public;

create function praxis_bulk.protect_lifecycle_audit_bootstrap()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if TG_OP = 'DELETE' or new.bootstrap_version is distinct from old.bootstrap_version
       or old.phase <> 'PENDING' or new.phase <> 'COMPLETE' then
        raise exception 'lifecycle audit bootstrap transition forbidden' using errcode = '55000';
    end if;
    return new;
end;
$$;
revoke all on function praxis_bulk.protect_lifecycle_audit_bootstrap() from public;

create trigger praxis_bulk_lifecycle_audit_bootstrap_protect
before update or delete on praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap
for each row execute function praxis_bulk.protect_lifecycle_audit_bootstrap();

create table praxis_bulk.praxis_bulk_lifecycle_audit (
    audit_id uuid not null,
    namespace_id text not null,
    deployment_id text not null,
    operation_id text not null,
    intent text not null,
    actor text not null,
    realm text not null,
    previous_generation bigint,
    resulting_generation bigint not null,
    resulting_state text not null,
    correlation_id text not null,
    executed_at timestamptz not null,
    constraint praxis_bulk_lifecycle_audit_pkey primary key (audit_id),
    constraint praxis_bulk_lifecycle_audit_namespace_fkey foreign key (namespace_id)
        references praxis_bulk.praxis_bulk_namespace_binding(namespace_id) on delete restrict,
    constraint praxis_bulk_lifecycle_audit_deployment_fkey foreign key (deployment_id)
        references praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) on delete restrict,
    constraint praxis_bulk_lifecycle_audit_operation_check check (
        operation_id <> '' and operation_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' and char_length(operation_id) <= 200),
    constraint praxis_bulk_lifecycle_audit_intent_check check (
        intent in ('PUBLISH', 'RECONCILE', 'SUSPEND', 'PUBLISH_ALL', 'RECONCILE_ALL', 'SUSPEND_ALL')),
    constraint praxis_bulk_lifecycle_audit_actor_check check (
        actor <> '' and actor !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' and char_length(actor) <= 128),
    constraint praxis_bulk_lifecycle_audit_realm_check check (
        realm <> '' and realm !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' and char_length(realm) <= 64),
    constraint praxis_bulk_lifecycle_audit_correlation_check check (
        correlation_id <> '' and correlation_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' and char_length(correlation_id) <= 128),
    constraint praxis_bulk_lifecycle_audit_generation_check check (
        (previous_generation is null or previous_generation >= 0) and resulting_generation >= 0),
    constraint praxis_bulk_lifecycle_audit_state_check check (
        resulting_state in ('READY', 'SUSPENDED', 'UNCOMPOSED', 'PUBLISHED'))
);

create index praxis_bulk_lifecycle_audit_ns_executed_idx
    on praxis_bulk.praxis_bulk_lifecycle_audit using btree (namespace_id, executed_at desc, audit_id desc);

revoke all on table praxis_bulk.praxis_bulk_lifecycle_audit from public;
grant select, insert on praxis_bulk.praxis_bulk_lifecycle_audit to praxis_bulk_control_owner;

create function praxis_bulk.reject_lifecycle_audit_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    raise exception 'lifecycle audit is immutable' using errcode = '55000';
end;
$$;
revoke all on function praxis_bulk.reject_lifecycle_audit_mutation() from public;

create trigger praxis_bulk_lifecycle_audit_immutable
before update or delete on praxis_bulk.praxis_bulk_lifecycle_audit
for each row execute function praxis_bulk.reject_lifecycle_audit_mutation();

create function praxis_bulk.suspend_operation_and_publication(
    p_namespace_id text, p_deployment_id text, p_operation_id text,
    p_expected_generation bigint, p_expected_publication_generation bigint)
returns table(applied boolean, operation_generation bigint, publication_generation bigint)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_publication praxis_bulk.praxis_bulk_openapi_publication%rowtype;
    v_control praxis_bulk.praxis_bulk_operation_control%rowtype;
    v_operation record;
begin
    if p_namespace_id is null or p_namespace_id = ''
       or p_namespace_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_deployment_id is null or p_deployment_id = ''
       or p_deployment_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_operation_id is null or p_operation_id = ''
       or p_operation_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_expected_generation is null or p_expected_generation < 0
       or p_expected_publication_generation is null or p_expected_publication_generation < 0 then
        raise exception 'atomic suspension identity or generation is invalid' using errcode = '22023';
    end if;
    if p_expected_generation = 9223372036854775807
       or p_expected_publication_generation = 9223372036854775807 then
        raise exception 'atomic suspension generation cannot advance' using errcode = '22003';
    end if;
    if not exists (select 1 from praxis_bulk.praxis_bulk_namespace_binding b
                   where b.namespace_id = p_namespace_id and b.deployment_id = p_deployment_id) then
        raise exception 'bulk deployment binding is unavailable' using errcode = '55000';
    end if;

    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.deployment_id = p_deployment_id order by b.namespace_id for share;
    select p.* into v_publication from praxis_bulk.praxis_bulk_openapi_publication p
        where p.deployment_id = p_deployment_id for update;
    if not found then
        raise exception 'bulk OpenAPI publication row is missing' using errcode = '55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                 where b.deployment_id = p_deployment_id)
        order by c.namespace_id, c.operation_id for update;
    select c.* into v_control from praxis_bulk.praxis_bulk_operation_control c
        where c.namespace_id = p_namespace_id and c.operation_id = p_operation_id;
    if not found then
        raise exception 'bulk operation-control row is missing' using errcode = '55000';
    end if;
    if v_control.generation <> p_expected_generation
       or v_publication.generation <> p_expected_publication_generation then
        return query select false, v_control.generation, v_publication.generation;
        return;
    end if;
    if exists (select 1 from praxis_bulk.praxis_bulk_operation_control c
               where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                         where b.deployment_id = p_deployment_id)
                 and (c.state = 'READY' or (c.namespace_id = p_namespace_id and c.operation_id = p_operation_id))
                 and c.generation = 9223372036854775807) then
        raise exception 'bulk operation generation cannot advance' using errcode = '22003';
    end if;

    for v_operation in select c.namespace_id, c.operation_id from praxis_bulk.praxis_bulk_operation_control c
                       where c.namespace_id in (select b.namespace_id from praxis_bulk.praxis_bulk_namespace_binding b
                                                where b.deployment_id = p_deployment_id)
                         and (c.state = 'READY' or (c.namespace_id = p_namespace_id and c.operation_id = p_operation_id))
                       order by c.namespace_id, c.operation_id loop
        update praxis_bulk.praxis_bulk_operation_control c
            set state = 'SUSPENDED', generation = c.generation + 1,
                descriptor_fingerprint = null, structural_revision = null,
                publication_generation = null, publication_document_digest = null,
                updated_at = greatest(clock_timestamp(), c.updated_at)
            where c.namespace_id = v_operation.namespace_id and c.operation_id = v_operation.operation_id;
    end loop;

    update praxis_bulk.praxis_bulk_openapi_publication p
        set state = 'SUSPENDED', generation = v_publication.generation + 1,
            document_digest = null, updated_at = greatest(clock_timestamp(), p.updated_at)
        where p.deployment_id = p_deployment_id;

    return query select true, v_control.generation + 1, v_publication.generation + 1;
end;
$$;

create function praxis_bulk.record_lifecycle_audit(
    p_audit_id uuid, p_namespace_id text, p_deployment_id text, p_operation_id text,
    p_intent text, p_actor text, p_realm text, p_previous_generation bigint,
    p_resulting_generation bigint, p_resulting_state text, p_correlation_id text)
returns timestamptz
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_now timestamptz := clock_timestamp();
begin
    if p_audit_id is null
       or p_namespace_id is null or p_namespace_id = '' or p_namespace_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_deployment_id is null or p_deployment_id = '' or p_deployment_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_operation_id is null or p_operation_id = '' or p_operation_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' or char_length(p_operation_id) > 200
       or p_intent is null or p_intent not in ('PUBLISH', 'RECONCILE', 'SUSPEND', 'PUBLISH_ALL', 'RECONCILE_ALL', 'SUSPEND_ALL')
       or p_actor is null or p_actor = '' or p_actor ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' or char_length(p_actor) > 128
       or p_realm is null or p_realm = '' or p_realm ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' or char_length(p_realm) > 64
       or p_correlation_id is null or p_correlation_id = '' or p_correlation_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' or char_length(p_correlation_id) > 128
       or (p_previous_generation is not null and p_previous_generation < 0)
       or p_resulting_generation is null or p_resulting_generation < 0
       or p_resulting_state is null or p_resulting_state not in ('READY', 'SUSPENDED', 'UNCOMPOSED', 'PUBLISHED') then
        raise exception 'lifecycle audit entry parameters are invalid' using errcode = '22023';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.namespace_id = p_namespace_id and b.deployment_id = p_deployment_id for share;
    if not found then
        raise exception 'bulk deployment binding is unavailable for audit' using errcode = '55000';
    end if;

    insert into praxis_bulk.praxis_bulk_lifecycle_audit (
        audit_id, namespace_id, deployment_id, operation_id, intent, actor, realm,
        previous_generation, resulting_generation, resulting_state, correlation_id, executed_at)
    values (
        p_audit_id, p_namespace_id, p_deployment_id, p_operation_id, p_intent, p_actor, p_realm,
        p_previous_generation, p_resulting_generation, p_resulting_state, p_correlation_id, v_now);
    return v_now;
end;
$$;

create function praxis_bulk.read_lifecycle_audit(p_namespace_id text, p_limit integer)
returns table(
    audit_id uuid, namespace_id text, deployment_id text, operation_id text,
    intent text, actor text, realm text, previous_generation bigint,
    resulting_generation bigint, resulting_state text, correlation_id text, executed_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
    if p_namespace_id is null or p_namespace_id = '' or p_namespace_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_limit is null or p_limit < 1 or p_limit > 500 then
        raise exception 'lifecycle audit query parameters are invalid' using errcode = '22023';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.namespace_id = p_namespace_id for share;
    if not found then
        raise exception 'bulk namespace binding is unavailable for audit read' using errcode = '55000';
    end if;

    return query
        select a.audit_id, a.namespace_id, a.deployment_id, a.operation_id,
               a.intent, a.actor, a.realm, a.previous_generation,
               a.resulting_generation, a.resulting_state, a.correlation_id, a.executed_at
        from praxis_bulk.praxis_bulk_lifecycle_audit a
        where a.namespace_id = p_namespace_id
        order by a.executed_at desc, a.audit_id desc
        limit p_limit;
end;
$$;

revoke all on function praxis_bulk.suspend_operation_and_publication(text, text, text, bigint, bigint) from public;
revoke all on function praxis_bulk.record_lifecycle_audit(uuid, text, text, text, text, text, text, bigint, bigint, text, text) from public;
revoke all on function praxis_bulk.read_lifecycle_audit(text, integer) from public;

do $$ begin
    execute pg_catalog.format('grant praxis_bulk_control_owner to %I with inherit false, set true granted by current_user', current_user);
end $$;
grant create on schema praxis_bulk to praxis_bulk_control_owner;
alter function praxis_bulk.suspend_operation_and_publication(text, text, text, bigint, bigint) owner to praxis_bulk_control_owner;
alter function praxis_bulk.record_lifecycle_audit(uuid, text, text, text, text, text, text, bigint, bigint, text, text) owner to praxis_bulk_control_owner;
alter function praxis_bulk.read_lifecycle_audit(text, integer) owner to praxis_bulk_control_owner;
revoke create on schema praxis_bulk from praxis_bulk_control_owner;
do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_control_owner from %I granted by current_user', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.roleid
               where r.rolname = 'praxis_bulk_control_owner'
               and not (m.admin_option and not m.inherit_option and not m.set_option
                  and m.member=(select nspowner from pg_catalog.pg_namespace where nspname='praxis_bulk')
                  and exists(select 1 from pg_catalog.pg_roles bootstrap
                             where bootstrap.oid=m.grantor and bootstrap.rolsuper)
                  and (select count(*) from pg_catalog.pg_auth_members same_actor
                       where same_actor.roleid=m.roleid and same_actor.member=m.member
                         and same_actor.admin_option and not same_actor.inherit_option and not same_actor.set_option)=1))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner'
               and not (m.admin_option and not m.inherit_option and not m.set_option
                  and m.member=(select nspowner from pg_catalog.pg_namespace where nspname='praxis_bulk')
                  and exists(select 1 from pg_catalog.pg_roles bootstrap
                             where bootstrap.oid=m.grantor and bootstrap.rolsuper)
                  and (select count(*) from pg_catalog.pg_auth_members same_actor
                       where same_actor.roleid=m.roleid and same_actor.member=m.member
                         and same_actor.admin_option and not same_actor.inherit_option and not same_actor.set_option)=1)) then
        raise exception 'bulk control-owner membership was not fully revoked';
    end if;
end $$;
