-- B5b.1b.A: a physical database has one immutable declared capacity identity.
-- Only the trusted schema owner provisions or fences it. Runtime receives SELECT only.
create table praxis_bulk.praxis_bulk_capacity_read_bootstrap (
    bootstrap_version smallint constraint capacity_read_bootstrap_pkey primary key
        constraint capacity_read_bootstrap_version_check check (bootstrap_version = 18),
    phase text not null constraint capacity_read_bootstrap_phase_check
        check (phase in ('PENDING', 'COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_capacity_read_bootstrap (bootstrap_version, phase)
values (18, 'PENDING');

create table praxis_bulk.praxis_bulk_capacity_marker (
    marker_id smallint default 1 constraint capacity_marker_pkey primary key
        constraint capacity_marker_singleton check (marker_id = 1),
    database_id uuid not null constraint capacity_marker_database_unique unique,
    deployment_id text not null,
    tenant_id text not null,
    environment text not null,
    binding_id text not null,
    binding_generation bigint not null constraint capacity_marker_generation_positive
        check (binding_generation > 0),
    attestation_id uuid not null constraint capacity_marker_attestation_unique unique,
    authority_id uuid not null,
    authority_epoch bigint not null constraint capacity_marker_epoch_positive
        check (authority_epoch > 0),
    state text not null constraint capacity_marker_state_check
        check (state in ('PROVISIONED', 'ACTIVE', 'FENCED')),
    constraint capacity_marker_full_identity_unique
        unique (marker_id, database_id, deployment_id, tenant_id, environment,
            binding_id, binding_generation, attestation_id, authority_id, authority_epoch),
    constraint capacity_marker_deployment_canonical
        check (deployment_id <> '' and deployment_id = btrim(deployment_id)
           and deployment_id !~ '[[:cntrl:]]'),
    constraint capacity_marker_tenant_canonical
        check (tenant_id <> '' and tenant_id = btrim(tenant_id)
           and tenant_id !~ '[[:cntrl:]]'),
    constraint capacity_marker_environment_canonical
        check (environment <> '' and environment = btrim(environment)
           and environment !~ '[[:cntrl:]]'),
    constraint capacity_marker_binding_canonical
        check (binding_id <> '' and binding_id = btrim(binding_id)
           and binding_id !~ '[[:cntrl:]]')
);

create table praxis_bulk.praxis_bulk_capacity_installation (
    token_id uuid constraint capacity_installation_pkey primary key,
    marker_id smallint not null default 1 constraint capacity_installation_singleton
        check (marker_id = 1),
    database_id uuid not null,
    deployment_id text not null,
    tenant_id text not null,
    environment text not null,
    binding_id text not null,
    binding_generation bigint not null constraint capacity_installation_generation_positive
        check (binding_generation > 0),
    attestation_id uuid not null,
    authority_id uuid not null,
    authority_epoch bigint not null constraint capacity_installation_epoch_positive
        check (authority_epoch > 0),
    request_id uuid not null,
    capacity_class text not null constraint capacity_installation_class_check
        check (capacity_class in ('ACTIVE', 'QUEUE')),
    token_ordinal integer not null constraint capacity_installation_ordinal_positive
        check (token_ordinal > 0),
    payload_digest text not null constraint capacity_installation_digest_check
        check (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    token_state text not null constraint capacity_installation_token_state_check
        check (token_state = 'ISSUED'),
    constraint capacity_installation_marker_fk
        foreign key (marker_id, database_id, deployment_id, tenant_id, environment,
                 binding_id, binding_generation, attestation_id, authority_id, authority_epoch)
      references praxis_bulk.praxis_bulk_capacity_marker
        (marker_id, database_id, deployment_id, tenant_id, environment,
         binding_id, binding_generation, attestation_id, authority_id, authority_epoch) on delete restrict,
    constraint capacity_installation_request_ordinal_unique unique (request_id, token_ordinal)
);

create function praxis_bulk.protect_capacity_marker()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if TG_OP = 'DELETE' then
        raise exception 'capacity marker cannot be deleted' using errcode = '55000';
    end if;
    if TG_OP = 'UPDATE' then
        if (new.marker_id, new.database_id, new.deployment_id, new.tenant_id,
            new.environment, new.binding_id, new.binding_generation, new.attestation_id,
            new.authority_id, new.authority_epoch)
           is distinct from
           (old.marker_id, old.database_id, old.deployment_id, old.tenant_id,
            old.environment, old.binding_id, old.binding_generation, old.attestation_id,
            old.authority_id, old.authority_epoch)
           or not ((old.state = 'PROVISIONED' and new.state in ('ACTIVE', 'FENCED'))
               or (old.state = 'ACTIVE' and new.state = 'FENCED')) then
            raise exception 'capacity marker transition forbidden' using errcode = '55000';
        end if;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_capacity_marker_protect
before update or delete on praxis_bulk.praxis_bulk_capacity_marker
for each row execute function praxis_bulk.protect_capacity_marker();

create function praxis_bulk.reject_capacity_installation_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    raise exception 'capacity installation is immutable' using errcode = '55000';
end;
$$;
create trigger praxis_bulk_capacity_installation_immutable
before update or delete on praxis_bulk.praxis_bulk_capacity_installation
for each row execute function praxis_bulk.reject_capacity_installation_mutation();

create function praxis_bulk.protect_capacity_read_bootstrap()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if TG_OP = 'DELETE' then
        raise exception 'capacity read bootstrap cannot be deleted' using errcode = '55000';
    end if;
    if new.bootstrap_version is distinct from old.bootstrap_version
       or old.phase <> 'PENDING' or new.phase <> 'COMPLETE' then
        raise exception 'capacity read bootstrap transition forbidden' using errcode = '55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_capacity_read_bootstrap_protect
before update or delete on praxis_bulk.praxis_bulk_capacity_read_bootstrap
for each row execute function praxis_bulk.protect_capacity_read_bootstrap();

revoke all on praxis_bulk.praxis_bulk_capacity_read_bootstrap,
              praxis_bulk.praxis_bulk_capacity_marker,
              praxis_bulk.praxis_bulk_capacity_installation from public;
revoke all on function praxis_bulk.protect_capacity_marker(),
                       praxis_bulk.reject_capacity_installation_mutation(),
                       praxis_bulk.protect_capacity_read_bootstrap() from public;
