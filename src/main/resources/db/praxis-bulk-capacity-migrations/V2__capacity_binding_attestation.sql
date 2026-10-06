-- B5b.1b.A: one immutable physical attestation per declared binding.
-- Verify V1 before changing its photographed catalog; never bless pre-existing drift.
do $$
declare v_expected text;
        v_actual text;
begin
    select pg_catalog.obj_description(to_regnamespace('praxis_bulk_capacity'), 'pg_namespace')
      into v_expected;
    if v_expected is null then
        raise exception 'capacity authority V1 catalog photograph missing' using errcode = '55000';
    end if;
    select pg_catalog.jsonb_build_object(
            'schema', (select pg_catalog.jsonb_build_array(n.nspname,n.nspacl,
                    pg_catalog.pg_get_userbyid(n.nspowner))
                from pg_catalog.pg_namespace n where n.nspname='praxis_bulk_capacity'),
            'columns', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname, a.attnum, a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod),
                    a.attnotnull, pg_catalog.pg_get_expr(d.adbin,d.adrelid),a.attacl,
                    a.attgenerated,a.attidentity) order by c.relname,a.attnum)
                from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                join pg_catalog.pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                left join pg_catalog.pg_attrdef d on d.adrelid=c.oid and d.adnum=a.attnum
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')),
            'constraints', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,k.conname,k.contype,pg_catalog.pg_get_constraintdef(k.oid,true))
                    order by c.relname,k.conname)
                from pg_catalog.pg_constraint k join pg_catalog.pg_class c on c.oid=k.conrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'indexes', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,i.relname,pg_catalog.pg_get_indexdef(i.oid)) order by c.relname,i.relname)
                from pg_catalog.pg_index x join pg_catalog.pg_class c on c.oid=x.indrelid
                join pg_catalog.pg_class i on i.oid=x.indexrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'triggers', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,t.tgname,t.tgenabled,pg_catalog.pg_get_triggerdef(t.oid))
                    order by c.relname,t.tgname)
                from pg_catalog.pg_trigger t join pg_catalog.pg_class c on c.oid=t.tgrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'functions', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    p.proname,pg_catalog.pg_get_function_identity_arguments(p.oid),p.prosrc,
                    p.prosecdef,p.proconfig,p.proacl,pg_catalog.pg_get_userbyid(p.proowner))
                    order by p.proname,pg_catalog.pg_get_function_identity_arguments(p.oid))
                from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace
                where n.nspname='praxis_bulk_capacity'),
            'relations', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,c.relkind,c.relrowsecurity,c.relacl,
                    pg_catalog.pg_get_userbyid(c.relowner)) order by c.relname)
                from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p'))
        )::text into v_actual;
    if v_expected::jsonb <> v_actual::jsonb then
        raise exception 'capacity authority V1 catalog drift' using errcode = '55000';
    end if;
end;
$$;

create table praxis_bulk_capacity.binding_attestation (
    attestation_id uuid constraint binding_attestation_pkey primary key,
    database_id uuid not null constraint binding_attestation_database_unique unique,
    binding_id text not null constraint binding_attestation_binding_unique unique,
    deployment_id text not null,
    tenant_id text not null,
    environment text not null,
    binding_generation bigint not null constraint binding_attestation_generation_positive
        check (binding_generation > 0),
    authority_id uuid not null,
    authority_epoch bigint not null constraint binding_attestation_epoch_positive
        check (authority_epoch > 0),
    constraint binding_attestation_binding_fk foreign key (binding_id, deployment_id, tenant_id)
        references praxis_bulk_capacity.capacity_binding(binding_id, deployment_id, tenant_id)
        on delete restrict,
    constraint binding_attestation_identity_fk foreign key (deployment_id, environment)
        references praxis_bulk_capacity.authority_identity(deployment_id, environment)
        on delete restrict,
    constraint binding_attestation_binding_canonical
        check (binding_id <> '' and binding_id = btrim(binding_id) and binding_id !~ '[[:cntrl:]]'),
    constraint binding_attestation_deployment_canonical
        check (deployment_id <> '' and deployment_id = btrim(deployment_id) and deployment_id !~ '[[:cntrl:]]'),
    constraint binding_attestation_tenant_canonical
        check (tenant_id <> '' and tenant_id = btrim(tenant_id) and tenant_id !~ '[[:cntrl:]]'),
    constraint binding_attestation_environment_canonical
        check (environment <> '' and environment = btrim(environment) and environment !~ '[[:cntrl:]]')
);

grant select, insert on praxis_bulk_capacity.binding_attestation to praxis_bulk_capacity_definer;
-- The binding row is locked by the definer during registration; no owner credential reaches callers.

create function praxis_bulk_capacity.register_attestation(
    p_deployment text, p_tenant text, p_environment text, p_binding text,
    p_generation bigint, p_database uuid, p_attestation uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
declare v_binding praxis_bulk_capacity.capacity_binding%rowtype;
        v_identity praxis_bulk_capacity.authority_identity%rowtype;
        v_existing praxis_bulk_capacity.binding_attestation%rowtype;
begin
    if p_deployment is null or p_tenant is null or p_environment is null or p_binding is null
       or p_generation is null or p_generation <= 0 or p_database is null or p_attestation is null
       or p_deployment = '' or p_deployment <> btrim(p_deployment)
       or p_tenant = '' or p_tenant <> btrim(p_tenant)
       or p_environment = '' or p_environment <> btrim(p_environment)
       or p_binding = '' or p_binding <> btrim(p_binding)
       or p_deployment ~ '[[:cntrl:]]' or p_tenant ~ '[[:cntrl:]]'
       or p_environment ~ '[[:cntrl:]]' or p_binding ~ '[[:cntrl:]]' then
        raise exception 'invalid binding attestation' using errcode = '22023';
    end if;
    select * into v_identity from praxis_bulk_capacity.authority_identity
     where deployment_id = p_deployment and environment = p_environment;
    if not found then
        raise exception 'capacity authority identity unavailable' using errcode = 'PZ003';
    end if;
    select * into v_binding from praxis_bulk_capacity.capacity_binding
     where binding_id = p_binding for update;
    if not found or v_binding.deployment_id <> p_deployment
       or v_binding.tenant_id <> p_tenant or v_binding.environment <> p_environment
       or v_binding.binding_generation <> p_generation then
        raise exception 'binding attestation mismatch' using errcode = 'PZ001';
    end if;
    select * into v_existing from praxis_bulk_capacity.binding_attestation
     where binding_id = p_binding;
    if found then
        if v_existing.deployment_id <> p_deployment or v_existing.tenant_id <> p_tenant
           or v_existing.environment <> p_environment or v_existing.binding_generation <> p_generation
           or v_existing.database_id <> p_database or v_existing.attestation_id <> p_attestation
           or v_existing.authority_id <> v_identity.authority_id
           or v_existing.authority_epoch <> v_identity.authority_epoch then
            raise exception 'binding attestation conflict' using errcode = 'PZ001';
        end if;
        return;
    end if;
    insert into praxis_bulk_capacity.binding_attestation
        (attestation_id, database_id, binding_id, deployment_id, tenant_id, environment,
         binding_generation, authority_id, authority_epoch)
    values (p_attestation, p_database, p_binding, p_deployment, p_tenant, p_environment,
            p_generation, v_identity.authority_id, v_identity.authority_epoch);
exception when unique_violation then
    raise exception 'binding attestation conflict' using errcode = 'PZ001';
end;
$$;

create function praxis_bulk_capacity.read_attestation(p_attestation uuid)
returns table(attestation_id uuid, database_id uuid, binding_id text, deployment_id text,
              tenant_id text, environment text, binding_generation bigint,
              authority_id uuid, authority_epoch bigint)
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
    select a.attestation_id, a.database_id, a.binding_id, a.deployment_id, a.tenant_id,
           a.environment, a.binding_generation, a.authority_id, a.authority_epoch
      from praxis_bulk_capacity.binding_attestation a
     where a.attestation_id = p_attestation
$$;

create function praxis_bulk_capacity.read_issued_token(p_token uuid)
returns table(token_id uuid, request_id uuid, deployment_id text, tenant_id text,
              environment text, binding_id text, binding_generation bigint,
              capacity_class text, token_ordinal integer, payload_digest text,
              token_state text, database_id uuid, attestation_id uuid,
              authority_id uuid, authority_epoch bigint)
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
    select t.token_id, t.request_id, t.deployment_id, t.tenant_id, a.environment,
           t.binding_id, t.binding_generation, t.capacity_class, t.token_ordinal,
           r.payload_digest, t.state, a.database_id, a.attestation_id,
           a.authority_id, a.authority_epoch
      from praxis_bulk_capacity.capacity_token t
      join praxis_bulk_capacity.capacity_request r on r.request_id = t.request_id
      join praxis_bulk_capacity.binding_attestation a
        on a.binding_id = t.binding_id and a.deployment_id = t.deployment_id
       and a.tenant_id = t.tenant_id and a.binding_generation = t.binding_generation
     where t.token_id = p_token
$$;

grant create on schema praxis_bulk_capacity to praxis_bulk_capacity_definer;
do $$ begin execute pg_catalog.format('grant praxis_bulk_capacity_definer to %I', current_user); end; $$;
alter function praxis_bulk_capacity.register_attestation(text,text,text,text,bigint,uuid,uuid)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.read_attestation(uuid)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.read_issued_token(uuid)
    owner to praxis_bulk_capacity_definer;
set local role praxis_bulk_capacity_definer;
revoke all on function praxis_bulk_capacity.register_attestation(text,text,text,text,bigint,uuid,uuid) from public;
revoke all on function praxis_bulk_capacity.read_attestation(uuid) from public;
revoke all on function praxis_bulk_capacity.read_issued_token(uuid) from public;
grant execute on function praxis_bulk_capacity.register_attestation(text,text,text,text,bigint,uuid,uuid)
    to praxis_bulk_capacity_provisioner;
grant execute on function praxis_bulk_capacity.read_attestation(uuid),
    praxis_bulk_capacity.read_issued_token(uuid) to praxis_bulk_capacity_reader;
reset role;
do $$ begin execute pg_catalog.format('revoke praxis_bulk_capacity_definer from %I', current_user); end; $$;
revoke create on schema praxis_bulk_capacity from praxis_bulk_capacity_definer;

-- Refresh the exact catalog photograph after the V2 DDL in the same Flyway transaction.
do $$
declare v_manifest text;
begin
    select pg_catalog.jsonb_build_object(
            'schema', (select pg_catalog.jsonb_build_array(n.nspname,n.nspacl,
                    pg_catalog.pg_get_userbyid(n.nspowner))
                from pg_catalog.pg_namespace n where n.nspname='praxis_bulk_capacity'),
            'columns', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname, a.attnum, a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod),
                    a.attnotnull, pg_catalog.pg_get_expr(d.adbin,d.adrelid),a.attacl,
                    a.attgenerated,a.attidentity) order by c.relname,a.attnum)
                from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                join pg_catalog.pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                left join pg_catalog.pg_attrdef d on d.adrelid=c.oid and d.adnum=a.attnum
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p')),
            'constraints', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,k.conname,k.contype,pg_catalog.pg_get_constraintdef(k.oid,true))
                    order by c.relname,k.conname)
                from pg_catalog.pg_constraint k join pg_catalog.pg_class c on c.oid=k.conrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'indexes', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,i.relname,pg_catalog.pg_get_indexdef(i.oid)) order by c.relname,i.relname)
                from pg_catalog.pg_index x join pg_catalog.pg_class c on c.oid=x.indrelid
                join pg_catalog.pg_class i on i.oid=x.indexrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'triggers', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,t.tgname,t.tgenabled,pg_catalog.pg_get_triggerdef(t.oid))
                    order by c.relname,t.tgname)
                from pg_catalog.pg_trigger t join pg_catalog.pg_class c on c.oid=t.tgrelid
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity'),
            'functions', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    p.proname,pg_catalog.pg_get_function_identity_arguments(p.oid),p.prosrc,
                    p.prosecdef,p.proconfig,p.proacl,pg_catalog.pg_get_userbyid(p.proowner))
                    order by p.proname,pg_catalog.pg_get_function_identity_arguments(p.oid))
                from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace
                where n.nspname='praxis_bulk_capacity'),
            'relations', (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_array(
                    c.relname,c.relkind,c.relrowsecurity,c.relacl,
                    pg_catalog.pg_get_userbyid(c.relowner)) order by c.relname)
                from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind in ('r','p'))
        )::text into v_manifest;
    execute pg_catalog.format('comment on schema praxis_bulk_capacity is %L', v_manifest);
end;
$$;
