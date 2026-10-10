-- Keep the descriptor tuple which authorized each proposal/execution. Old rows
-- remain NULL/unbound and cannot acquire authority from a later READY publication.
alter table praxis_bulk.praxis_bulk_proposal
    add column control_generation bigint,
    add column control_descriptor_fingerprint text,
    add column control_structural_revision text,
    add constraint praxis_bulk_proposal_control_tuple_check check (
        (control_generation is null and control_descriptor_fingerprint is null
            and control_structural_revision is null)
        or (control_generation is not null and control_generation >= 1
            and control_descriptor_fingerprint is not null
            and control_descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$'
            and control_structural_revision is not null
            and btrim(control_structural_revision) <> ''
            and length(control_structural_revision) <= 200)
    );

alter table praxis_bulk.praxis_bulk_execution
    add column control_generation bigint,
    add column control_descriptor_fingerprint text,
    add constraint praxis_bulk_execution_control_tuple_check check (
        (control_generation is null and control_descriptor_fingerprint is null)
        or (control_generation is not null and control_generation >= 1
            and control_descriptor_fingerprint is not null
            and control_descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$')
    );

-- A previous application node may still know how to insert V6 rows. Reject those
-- inserts after V7: nullable columns preserve historical rows only, never grant
-- authority to a post-migration writer.
grant select (control_generation, control_descriptor_fingerprint, control_structural_revision)
    on praxis_bulk.praxis_bulk_proposal to praxis_bulk_control_owner;

create function praxis_bulk.guard_descriptor_fence()
returns trigger
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_control record;
    v_proposal record;
begin
    select c.state, c.generation, c.descriptor_fingerprint, c.structural_revision
      into v_control
      from praxis_bulk.lock_operation_control(new.namespace_id, new.operation_id) c;
    if not found or v_control.state is distinct from 'READY'
       or new.control_generation is distinct from v_control.generation
       or new.control_descriptor_fingerprint is distinct from v_control.descriptor_fingerprint then
        raise exception 'bulk descriptor generation is not current' using errcode = '55000';
    end if;

    if tg_table_name = 'praxis_bulk_proposal' then
        if new.control_structural_revision is distinct from v_control.structural_revision then
            raise exception 'bulk proposal descriptor revision is not current' using errcode = '55000';
        end if;
    else
        if new.structural_revision is distinct from v_control.structural_revision then
            raise exception 'bulk execution descriptor revision is not current' using errcode = '55000';
        end if;
        select p.control_generation, p.control_descriptor_fingerprint, p.control_structural_revision
          into v_proposal
         from praxis_bulk.praxis_bulk_proposal p
         where p.proposal_id = new.proposal_id
           and p.namespace_id = new.namespace_id and p.operation_id = new.operation_id;
        if not found
           or v_proposal.control_generation is distinct from v_control.generation
           or v_proposal.control_descriptor_fingerprint is distinct from v_control.descriptor_fingerprint
           or v_proposal.control_structural_revision is distinct from v_control.structural_revision then
            raise exception 'bulk execution descriptor differs from its proposal' using errcode = '55000';
        end if;
    end if;
    return new;
end;
$$;
alter function praxis_bulk.guard_descriptor_fence() owner to praxis_bulk_control_owner;
revoke all on function praxis_bulk.guard_descriptor_fence() from public;

create trigger praxis_bulk_proposal_descriptor_fence
    before insert on praxis_bulk.praxis_bulk_proposal
    for each row execute function praxis_bulk.guard_descriptor_fence();
create trigger praxis_bulk_execution_descriptor_fence
    before insert on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.guard_descriptor_fence();

create function praxis_bulk.protect_execution_descriptor_binding()
returns trigger
language plpgsql
as $function$
begin
    if new.control_generation is distinct from old.control_generation
       or new.control_descriptor_fingerprint is distinct from old.control_descriptor_fingerprint then
        raise exception 'praxis_bulk.praxis_bulk_execution descriptor binding is immutable'
            using errcode = '55000';
    end if;
    return new;
end;
$function$;

create trigger praxis_bulk_execution_protect_descriptor_binding
    before update on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.protect_execution_descriptor_binding();
