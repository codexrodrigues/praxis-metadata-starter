-- Preparatory global publication ledger: one immutable document identity per deployment. R2 must bind operation readiness and public
-- serving to this generation before either may treat PUBLISHED as an admission fence.
do $$ begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_roles where rolname = 'praxis_bulk_control_owner'
                  and (rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.roleid
                  where r.rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner')
       or pg_catalog.has_schema_privilege('praxis_bulk_control_owner', 'praxis_bulk', 'CREATE') then
        raise exception 'bulk control-owner topology is unsafe before publication migration';
    end if;
end $$;
create table praxis_bulk.praxis_bulk_openapi_publication (
    deployment_id text not null,
    state text not null,
    generation bigint not null,
    document_digest text,
    updated_at timestamptz not null,
    constraint praxis_bulk_openapi_publication_pkey primary key (deployment_id),
    constraint praxis_bulk_openapi_publication_binding_fkey foreign key (deployment_id)
        references praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) on delete restrict,
    constraint praxis_bulk_openapi_publication_deployment_check check (
        deployment_id <> '' and deployment_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'),
    constraint praxis_bulk_openapi_publication_generation_check check (generation >= 0),
    constraint praxis_bulk_openapi_publication_state_check check (state in ('UNCOMPOSED', 'SUSPENDED', 'PUBLISHED')),
    constraint praxis_bulk_openapi_publication_digest_check check (
        (state = 'PUBLISHED' and document_digest is not null and document_digest ~ '^sha256:[0-9a-f]{64}$')
        or (state in ('UNCOMPOSED', 'SUSPENDED') and document_digest is null))
);

revoke all on table praxis_bulk.praxis_bulk_openapi_publication from public;
grant select on praxis_bulk.praxis_bulk_openapi_publication to praxis_bulk_control_owner;
grant update (state, generation, document_digest, updated_at)
    on praxis_bulk.praxis_bulk_openapi_publication to praxis_bulk_control_owner;
-- PostgreSQL requires an UPDATE privilege for FOR SHARE. The immutable binding
-- trigger still rejects every actual UPDATE; no bypass setting is introduced.
grant select, update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to praxis_bulk_control_owner;

create function praxis_bulk.lock_openapi_publication(p_namespace_id text, p_deployment_id text)
returns table(state text, generation bigint, document_digest text, updated_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
    if p_namespace_id is null or p_namespace_id = ''
       or p_namespace_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'
       or p_deployment_id is null or p_deployment_id = ''
       or p_deployment_id ~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]' then
        raise exception 'bulk OpenAPI publication identity is invalid' using errcode = '22023';
    end if;
    if not exists (select 1 from praxis_bulk.praxis_bulk_namespace_binding b
                   where b.namespace_id = p_namespace_id and b.deployment_id = p_deployment_id) then
        raise exception 'bulk OpenAPI deployment binding is unavailable' using errcode = '55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding b
        where b.deployment_id = p_deployment_id order by b.namespace_id for share;
    return query select c.state, c.generation, c.document_digest, c.updated_at
        from praxis_bulk.praxis_bulk_openapi_publication c
        where c.deployment_id = p_deployment_id for share;
    if not found then
        raise exception 'bulk OpenAPI publication row is missing' using errcode = '55000';
    end if;
end;
$$;

create function praxis_bulk.transition_openapi_publication(
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

-- Remove PUBLIC access while the migration role still owns these functions.
-- After ALTER OWNER a non-superuser migration role cannot revoke the new owner's ACL.
revoke all on function praxis_bulk.lock_openapi_publication(text, text) from public;
revoke all on function praxis_bulk.transition_openapi_publication(text, text, bigint, text, text) from public;

-- Assign function ownership only while holding the required temporary membership.
do $$ begin
    execute pg_catalog.format('grant praxis_bulk_control_owner to %I', current_user);
end $$;
grant create on schema praxis_bulk to praxis_bulk_control_owner;
alter function praxis_bulk.lock_openapi_publication(text, text) owner to praxis_bulk_control_owner;
alter function praxis_bulk.transition_openapi_publication(text, text, bigint, text, text) owner to praxis_bulk_control_owner;
revoke create on schema praxis_bulk from praxis_bulk_control_owner;
do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_control_owner from %I', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.roleid
               where r.rolname = 'praxis_bulk_control_owner')
       or exists (select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid = m.member
                  where r.rolname = 'praxis_bulk_control_owner') then
        raise exception 'bulk control-owner membership was not fully revoked';
    end if;
end $$;
-- Deployment owners grant EXECUTE explicitly to configured runtime/control roles,
-- respectively. Migrations never repair a revoked host ACL implicitly.
