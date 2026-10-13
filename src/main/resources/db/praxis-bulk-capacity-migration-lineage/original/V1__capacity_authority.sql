-- B5b.1a: rights issuer only. No job, local installation, withdrawal or reclamation.
do $$
declare v_name text;
begin
    foreach v_name in array array[
        'praxis_bulk_capacity_definer', 'praxis_bulk_capacity_provisioner',
        'praxis_bulk_capacity_allocator', 'praxis_bulk_capacity_reader'
    ] loop
        if not exists (select 1 from pg_catalog.pg_roles where rolname = v_name) then
            execute pg_catalog.format('create role %I nologin noinherit', v_name);
        end if;
        if exists (select 1 from pg_catalog.pg_roles
                   where rolname = v_name and (rolcanlogin or rolinherit or rolsuper
                       or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls)) then
            raise exception 'unsafe capacity role' using errcode = '55000';
        end if;
    end loop;
    if exists (
        select 1 from pg_catalog.pg_auth_members m
        join pg_catalog.pg_roles r on r.oid = m.roleid
        where r.rolname = 'praxis_bulk_capacity_definer'
    ) then
        raise exception 'capacity definer role has members' using errcode = '55000';
    end if;
end;
$$;

revoke all on schema praxis_bulk_capacity from public;
-- This is a dedicated database. PostgreSQL 14 still grants CREATE on public to PUBLIC.
revoke create on schema public from public;
grant usage on schema praxis_bulk_capacity to praxis_bulk_capacity_definer,
    praxis_bulk_capacity_provisioner, praxis_bulk_capacity_allocator,
    praxis_bulk_capacity_reader;

create table praxis_bulk_capacity.authority_identity (
    deployment_id text primary key,
    environment text not null,
    authority_id uuid not null unique,
    authority_epoch bigint not null check (authority_epoch > 0),
    unique (deployment_id, environment),
    check (deployment_id <> '' and deployment_id = btrim(deployment_id)),
    check (environment <> '' and environment = btrim(environment)),
    check (deployment_id !~ '[[:cntrl:]]' and environment !~ '[[:cntrl:]]')
);

create table praxis_bulk_capacity.deployment_capacity (
    deployment_id text primary key references praxis_bulk_capacity.authority_identity(deployment_id)
        on delete restrict
);

create table praxis_bulk_capacity.tenant_capacity (
    deployment_id text not null references praxis_bulk_capacity.deployment_capacity(deployment_id)
        on delete restrict,
    tenant_id text not null,
    primary key (deployment_id, tenant_id),
    check (tenant_id <> '' and tenant_id = btrim(tenant_id)
        and tenant_id !~ '[[:cntrl:]]')
);

create table praxis_bulk_capacity.capacity_binding (
    binding_id text primary key,
    deployment_id text not null,
    tenant_id text not null,
    environment text not null,
    binding_generation bigint not null check (binding_generation > 0),
    foreign key (deployment_id, tenant_id)
        references praxis_bulk_capacity.tenant_capacity(deployment_id, tenant_id) on delete restrict,
    foreign key (deployment_id, environment)
        references praxis_bulk_capacity.authority_identity(deployment_id, environment) on delete restrict,
    unique (binding_id, deployment_id, tenant_id),
    check (binding_id <> '' and binding_id = btrim(binding_id)
        and binding_id !~ '[[:cntrl:]]')
);

create table praxis_bulk_capacity.capacity_request (
    request_id uuid primary key,
    deployment_id text not null,
    tenant_id text not null,
    binding_id text not null,
    capacity_class text not null check (capacity_class in ('QUEUE', 'ACTIVE')),
    requested_count integer not null check (requested_count > 0),
    issued_count integer not null default 0 check (issued_count >= 0),
    payload_digest text not null check (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    state text not null default 'PENDING' check (state in ('PENDING', 'FULFILLED')),
    requested_at timestamptz not null default clock_timestamp(),
    foreign key (binding_id, deployment_id, tenant_id)
        references praxis_bulk_capacity.capacity_binding(binding_id, deployment_id, tenant_id)
        on delete restrict,
    unique (request_id, deployment_id, tenant_id, binding_id, capacity_class),
    check (issued_count <= requested_count),
    check ((state = 'PENDING' and issued_count < requested_count)
        or (state = 'FULFILLED' and issued_count = requested_count)),
    check ((capacity_class = 'ACTIVE' and requested_count <= 2)
        or (capacity_class = 'QUEUE' and requested_count <= 20))
);
create unique index capacity_one_pending_per_binding_class
    on praxis_bulk_capacity.capacity_request(deployment_id, tenant_id, binding_id, capacity_class)
    where state = 'PENDING';

create table praxis_bulk_capacity.capacity_token (
    token_id uuid primary key,
    request_id uuid not null references praxis_bulk_capacity.capacity_request(request_id)
        on delete restrict,
    token_ordinal integer not null check (token_ordinal > 0),
    deployment_id text not null,
    tenant_id text not null,
    binding_id text not null,
    capacity_class text not null check (capacity_class in ('QUEUE', 'ACTIVE')),
    binding_generation bigint not null check (binding_generation > 0),
    state text not null default 'ISSUED' check (state = 'ISSUED'),
    issued_at timestamptz not null default clock_timestamp(),
    unique (request_id, token_ordinal),
    foreign key (binding_id, deployment_id, tenant_id)
        references praxis_bulk_capacity.capacity_binding(binding_id, deployment_id, tenant_id)
        on delete restrict,
    foreign key (request_id, deployment_id, tenant_id, binding_id, capacity_class)
        references praxis_bulk_capacity.capacity_request
            (request_id, deployment_id, tenant_id, binding_id, capacity_class) on delete restrict
);
create index capacity_token_deployment_class on
    praxis_bulk_capacity.capacity_token(deployment_id, capacity_class);
create index capacity_token_tenant_class on
    praxis_bulk_capacity.capacity_token(deployment_id, tenant_id, capacity_class);

create table praxis_bulk_capacity.fairness_cursor (
    deployment_id text not null references praxis_bulk_capacity.deployment_capacity(deployment_id)
        on delete restrict,
    capacity_class text not null check (capacity_class in ('QUEUE', 'ACTIVE')),
    last_tenant_id text,
    cursor_epoch bigint not null default 0 check (cursor_epoch >= 0),
    primary key (deployment_id, capacity_class)
);

-- Only this NOLOGIN owner can read/write issuer tables through SECURITY DEFINER functions.
grant select on praxis_bulk_capacity.authority_identity,
    praxis_bulk_capacity.deployment_capacity,
    praxis_bulk_capacity.tenant_capacity,
    praxis_bulk_capacity.capacity_binding,
    praxis_bulk_capacity.capacity_request,
    praxis_bulk_capacity.capacity_token,
    praxis_bulk_capacity.fairness_cursor to praxis_bulk_capacity_definer;
grant insert on praxis_bulk_capacity.tenant_capacity,
    praxis_bulk_capacity.capacity_binding,
    praxis_bulk_capacity.capacity_request,
    praxis_bulk_capacity.capacity_token,
    praxis_bulk_capacity.fairness_cursor to praxis_bulk_capacity_definer;
-- PostgreSQL row locks require UPDATE privilege on a locked relation.
grant update (deployment_id) on praxis_bulk_capacity.deployment_capacity
    to praxis_bulk_capacity_definer;
grant update (tenant_id) on praxis_bulk_capacity.tenant_capacity
    to praxis_bulk_capacity_definer;
grant update (binding_id) on praxis_bulk_capacity.capacity_binding
    to praxis_bulk_capacity_definer;
grant update (issued_count, state) on praxis_bulk_capacity.capacity_request
    to praxis_bulk_capacity_definer;
grant update (last_tenant_id, cursor_epoch) on praxis_bulk_capacity.fairness_cursor
    to praxis_bulk_capacity_definer;

create function praxis_bulk_capacity.enroll_binding(
    p_deployment text, p_tenant text, p_binding text, p_generation bigint)
returns void language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
declare v_existing praxis_bulk_capacity.capacity_binding%rowtype;
begin
    if p_deployment is null or p_tenant is null or p_binding is null
       or p_generation is null or p_generation <= 0
       or p_tenant = '' or p_tenant <> btrim(p_tenant)
       or p_binding = '' or p_binding <> btrim(p_binding)
       or p_tenant ~ '[[:cntrl:]]' or p_binding ~ '[[:cntrl:]]' then
        raise exception 'invalid capacity binding' using errcode = '22023';
    end if;
    perform 1 from praxis_bulk_capacity.deployment_capacity
        where deployment_id = p_deployment for update;
    if not found then
        raise exception 'capacity authority unavailable' using errcode = 'PZ003';
    end if;
    insert into praxis_bulk_capacity.tenant_capacity(deployment_id, tenant_id)
        values (p_deployment, p_tenant) on conflict do nothing;
    select * into v_existing from praxis_bulk_capacity.capacity_binding
        where binding_id = p_binding for update;
    if found then
        if v_existing.deployment_id <> p_deployment or v_existing.tenant_id <> p_tenant
           or v_existing.binding_generation <> p_generation then
            raise exception 'capacity binding conflict' using errcode = 'PZ001';
        end if;
        return;
    end if;
    insert into praxis_bulk_capacity.capacity_binding
        (binding_id, deployment_id, tenant_id, environment, binding_generation)
    select p_binding, p_deployment, p_tenant, i.environment, p_generation
      from praxis_bulk_capacity.authority_identity i where i.deployment_id = p_deployment;
    if not found then
        raise exception 'capacity authority unavailable' using errcode = 'PZ003';
    end if;
end;
$$;

create function praxis_bulk_capacity.request_capacity(
    p_request uuid, p_deployment text, p_tenant text, p_binding text,
    p_class text, p_count integer, p_digest text)
returns void language plpgsql security definer
set search_path = pg_catalog, pg_temp as $$
declare v_request praxis_bulk_capacity.capacity_request%rowtype;
        v_generation bigint;
begin
    if p_request is null or p_deployment is null or p_tenant is null
       or p_binding is null or p_class is null or p_class not in ('QUEUE', 'ACTIVE')
       or p_deployment = '' or p_deployment <> btrim(p_deployment)
       or p_tenant = '' or p_tenant <> btrim(p_tenant)
       or p_binding = '' or p_binding <> btrim(p_binding)
       or p_deployment ~ '[[:cntrl:]]' or p_tenant ~ '[[:cntrl:]]'
       or p_binding ~ '[[:cntrl:]]'
       or p_count is null or p_count < 1
       or (p_class = 'ACTIVE' and p_count > 2)
       or (p_class = 'QUEUE' and p_count > 20)
       or p_digest is null or p_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception 'invalid capacity request' using errcode = '22023';
    end if;
    perform 1 from praxis_bulk_capacity.deployment_capacity
        where deployment_id = p_deployment for update;
    if not found then
        raise exception 'capacity authority unavailable' using errcode = 'PZ003';
    end if;
    perform 1 from praxis_bulk_capacity.tenant_capacity
        where deployment_id = p_deployment and tenant_id = p_tenant for update;
    if not found then
        raise exception 'capacity binding unavailable' using errcode = 'PZ003';
    end if;
    select binding_generation into v_generation
      from praxis_bulk_capacity.capacity_binding
     where binding_id = p_binding and deployment_id = p_deployment
       and tenant_id = p_tenant;
    if v_generation is null then
        raise exception 'capacity binding unavailable' using errcode = 'PZ003';
    end if;
    select * into v_request from praxis_bulk_capacity.capacity_request
     where request_id = p_request for update;
    if found then
        if v_request.deployment_id <> p_deployment or v_request.tenant_id <> p_tenant
           or v_request.binding_id <> p_binding or v_request.capacity_class <> p_class
           or v_request.requested_count <> p_count or v_request.payload_digest <> p_digest then
            raise exception 'capacity request conflict' using errcode = 'PZ001';
        end if;
        return;
    end if;
    if exists (
        select 1 from praxis_bulk_capacity.capacity_request
        where deployment_id = p_deployment and tenant_id = p_tenant
          and binding_id = p_binding and capacity_class = p_class and state = 'PENDING'
    ) then
        raise exception 'capacity request pending' using errcode = 'PZ002';
    end if;
    insert into praxis_bulk_capacity.capacity_request
        (request_id, deployment_id, tenant_id, binding_id, capacity_class,
         requested_count, payload_digest)
    values (p_request, p_deployment, p_tenant, p_binding, p_class, p_count, p_digest);
end;
$$;

create function praxis_bulk_capacity.allocate_next(p_deployment text, p_class text)
returns table(token_id uuid, request_id uuid, deployment_id text, tenant_id text, binding_id text,
              capacity_class text, binding_generation bigint, token_ordinal integer)
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_last text;
        v_tenant text;
        v_request praxis_bulk_capacity.capacity_request%rowtype;
        v_generation bigint;
        v_deployment_count bigint;
        v_tenant_count bigint;
        v_limit integer;
        v_token uuid;
begin
    if p_deployment is null or p_class is null or p_class not in ('QUEUE', 'ACTIVE') then
        raise exception 'invalid capacity allocation' using errcode = '22023';
    end if;
    perform 1 from praxis_bulk_capacity.deployment_capacity dc
        where dc.deployment_id = p_deployment for update;
    if not found then
        raise exception 'capacity authority unavailable' using errcode = 'PZ003';
    end if;
    select count(*) into v_deployment_count from praxis_bulk_capacity.capacity_token t
        where t.deployment_id = p_deployment and t.capacity_class = p_class;
    if v_deployment_count >= (case when p_class = 'ACTIVE' then 8 else 80 end) then
        return;
    end if;
    select c.last_tenant_id into v_last from praxis_bulk_capacity.fairness_cursor c
        where c.deployment_id = p_deployment and c.capacity_class = p_class;
    if not found then
        insert into praxis_bulk_capacity.fairness_cursor(deployment_id, capacity_class)
            values (p_deployment, p_class);
    end if;
    v_limit := case when p_class = 'ACTIVE' then 2 else 20 end;
    select r.tenant_id into v_tenant
      from praxis_bulk_capacity.capacity_request r
     where r.deployment_id = p_deployment and r.capacity_class = p_class
       and r.state = 'PENDING'
       and (select count(*) from praxis_bulk_capacity.capacity_token t
            where t.deployment_id = p_deployment and t.tenant_id = r.tenant_id
              and t.capacity_class = p_class) < v_limit
     group by r.tenant_id
     order by case when v_last is null or r.tenant_id > v_last then 0 else 1 end,
              r.tenant_id
     limit 1;
    if v_tenant is null then return; end if;
    perform 1 from praxis_bulk_capacity.tenant_capacity tc
        where tc.deployment_id = p_deployment and tc.tenant_id = v_tenant for update;
    select count(*) into v_tenant_count from praxis_bulk_capacity.capacity_token t
        where t.deployment_id = p_deployment and t.tenant_id = v_tenant
          and t.capacity_class = p_class;
    if v_tenant_count >= v_limit then return; end if;
    select * into v_request from praxis_bulk_capacity.capacity_request r
     where r.deployment_id = p_deployment and r.tenant_id = v_tenant
       and r.capacity_class = p_class and r.state = 'PENDING'
     order by r.requested_at, r.request_id limit 1 for update;
    if not found then return; end if;
    select b.binding_generation into v_generation
      from praxis_bulk_capacity.capacity_binding b
     where b.binding_id = v_request.binding_id
       and b.deployment_id = p_deployment and b.tenant_id = v_tenant;
    if v_generation is null then
        raise exception 'capacity binding unavailable' using errcode = 'PZ003';
    end if;
    v_token := gen_random_uuid();
    insert into praxis_bulk_capacity.capacity_token
        (token_id, request_id, token_ordinal, deployment_id, tenant_id,
         binding_id, capacity_class, binding_generation)
    values (v_token, v_request.request_id, v_request.issued_count + 1,
            p_deployment, v_tenant, v_request.binding_id, p_class, v_generation);
    update praxis_bulk_capacity.capacity_request
       set issued_count = issued_count + 1,
           state = case when issued_count + 1 = requested_count then 'FULFILLED' else 'PENDING' end
     where capacity_request.request_id = v_request.request_id;
    update praxis_bulk_capacity.fairness_cursor
       set last_tenant_id = v_tenant, cursor_epoch = cursor_epoch + 1
     where fairness_cursor.deployment_id = p_deployment
       and fairness_cursor.capacity_class = p_class;
    token_id := v_token;
    request_id := v_request.request_id;
    deployment_id := p_deployment;
    tenant_id := v_tenant;
    binding_id := v_request.binding_id;
    capacity_class := p_class;
    binding_generation := v_generation;
    token_ordinal := v_request.issued_count + 1;
    return next;
end;
$$;

create function praxis_bulk_capacity.find_issue(p_request uuid)
returns table(found boolean, deployment_id text, tenant_id text, binding_id text,
              capacity_class text, requested_count integer, issued_count integer,
              payload_digest text, token_ids uuid[])
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
    select true, r.deployment_id, r.tenant_id, r.binding_id, r.capacity_class,
           r.requested_count, r.issued_count, r.payload_digest,
           coalesce(array_agg(t.token_id order by t.token_ordinal)
                    filter (where t.token_id is not null), array[]::uuid[])
      from praxis_bulk_capacity.capacity_request r
      left join praxis_bulk_capacity.capacity_token t on t.request_id = r.request_id
     where r.request_id = p_request
     group by r.request_id
$$;

create function praxis_bulk_capacity.assert_authority_identity(
    p_deployment text, p_environment text, p_authority uuid, p_epoch bigint)
returns boolean language sql stable security definer set search_path = pg_catalog, pg_temp as $$
    select (select count(*) from praxis_bulk_capacity.authority_identity) = 1
       and exists (
           select 1 from praxis_bulk_capacity.authority_identity
           where deployment_id = p_deployment and environment = p_environment
             and authority_id = p_authority and authority_epoch = p_epoch
       )
$$;

-- A dedicated owner limits SECURITY DEFINER; caller roles have EXECUTE only.
-- PostgreSQL requires CREATE on the containing schema while function ownership moves.
-- This privilege is revoked in the same migration transaction before the catalog photograph.
grant create on schema praxis_bulk_capacity to praxis_bulk_capacity_definer;
do $$
begin
    execute pg_catalog.format('grant praxis_bulk_capacity_definer to %I', current_user);
end;
$$;
alter function praxis_bulk_capacity.enroll_binding(text,text,text,bigint)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.request_capacity(uuid,text,text,text,text,integer,text)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.allocate_next(text,text)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.find_issue(uuid)
    owner to praxis_bulk_capacity_definer;
alter function praxis_bulk_capacity.assert_authority_identity(text,text,uuid,bigint)
    owner to praxis_bulk_capacity_definer;
-- The exact function ACL grantor is the protected owner, including for non-superuser provisioners.
set local role praxis_bulk_capacity_definer;
revoke all on function praxis_bulk_capacity.enroll_binding(text,text,text,bigint) from public;
revoke all on function praxis_bulk_capacity.request_capacity(uuid,text,text,text,text,integer,text) from public;
revoke all on function praxis_bulk_capacity.allocate_next(text,text) from public;
revoke all on function praxis_bulk_capacity.find_issue(uuid) from public;
revoke all on function praxis_bulk_capacity.assert_authority_identity(text,text,uuid,bigint) from public;
grant execute on function praxis_bulk_capacity.enroll_binding(text,text,text,bigint)
    to praxis_bulk_capacity_provisioner;
grant execute on function praxis_bulk_capacity.request_capacity(uuid,text,text,text,text,integer,text),
    praxis_bulk_capacity.allocate_next(text,text) to praxis_bulk_capacity_allocator;
grant execute on function praxis_bulk_capacity.find_issue(uuid)
    to praxis_bulk_capacity_reader;
grant execute on function praxis_bulk_capacity.assert_authority_identity(text,text,uuid,bigint)
    to praxis_bulk_capacity_provisioner, praxis_bulk_capacity_allocator,
       praxis_bulk_capacity_reader;
reset role;
do $$
begin
    execute pg_catalog.format('revoke praxis_bulk_capacity_definer from %I', current_user);
end;
$$;
revoke create on schema praxis_bulk_capacity from praxis_bulk_capacity_definer;

-- The complete V1 structural photograph is installed in the same Flyway transaction as
-- its DDL. A crash before the separate identity bootstrap cannot silently bless drift.
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
