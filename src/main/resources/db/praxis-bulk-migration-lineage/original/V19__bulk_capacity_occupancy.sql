-- B5b.1b.B protected local occupancy. Non-rolling beta cutover requires drained
-- executors and suspended controls. This does not publish ASYNC capture or HTTP.
do $$ begin
    if not exists(select 1 from pg_catalog.pg_roles where rolname='praxis_bulk_capacity_owner') then
        create role praxis_bulk_capacity_owner nologin noinherit nosuperuser nocreatedb
            nocreaterole noreplication nobypassrls;
    end if;
    if not exists(select 1 from pg_catalog.pg_roles where rolname='praxis_bulk_capacity_owner'
        and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb
        and not rolcreaterole and not rolreplication and not rolbypassrls)
       or exists(select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r
          on r.oid=m.roleid or r.oid=m.member where r.rolname='praxis_bulk_capacity_owner') then
        raise exception 'capacity owner topology is unsafe' using errcode='55000';
    end if;
    execute pg_catalog.format('grant praxis_bulk_capacity_owner to %I',current_user);
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I',current_user);
    execute pg_catalog.format('grant praxis_bulk_control_owner to %I',current_user);
end $$;
grant usage,create on schema praxis_bulk to praxis_bulk_capacity_owner;

create table praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap (
    bootstrap_version smallint primary key
        constraint capacity_occupancy_bootstrap_version_check check (bootstrap_version=19),
    phase text not null check (phase in ('PENDING','COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap values (19,'PENDING');
create function praxis_bulk.protect_capacity_occupancy_bootstrap()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if TG_OP='DELETE' or new.bootstrap_version is distinct from old.bootstrap_version
       or old.phase<>'PENDING' or new.phase<>'COMPLETE' then
        raise exception 'occupancy bootstrap transition forbidden' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_capacity_occupancy_bootstrap_protect before update or delete
on praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap for each row
execute function praxis_bulk.protect_capacity_occupancy_bootstrap();

-- Storage JSON is decoded without rewriting its bytes, decimal spelling or hash.
do $$ begin
    if exists(select 1 from praxis_bulk.praxis_bulk_proposal where
        (replace(pg_catalog.convert_from(payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->'intent'->>'executionMode') is distinct from 'SYNC') then
        raise exception 'historical mode cannot be promoted to async provenance' using errcode='55000';
    end if;
end $$;
alter table praxis_bulk.praxis_bulk_proposal add column execution_mode text;
alter table praxis_bulk.praxis_bulk_proposal disable trigger praxis_bulk_proposal_reject_update;
update praxis_bulk.praxis_bulk_proposal
set execution_mode=(replace(pg_catalog.convert_from(payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->'intent'->>'executionMode');
alter table praxis_bulk.praxis_bulk_proposal enable trigger praxis_bulk_proposal_reject_update;
alter table praxis_bulk.praxis_bulk_proposal
    alter column execution_mode set not null,
    add constraint proposal_execution_mode_check check (execution_mode in ('SYNC','ASYNC')
       and (replace(pg_catalog.convert_from(payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->'intent'->>'executionMode') is not null
       and execution_mode is not distinct from
           (replace(pg_catalog.convert_from(payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->'intent'->>'executionMode')),
    add constraint proposal_execution_mode_key unique(proposal_id,execution_mode);
alter table praxis_bulk.praxis_bulk_execution add column execution_mode text,
    add column queue_token_id uuid references praxis_bulk.praxis_bulk_capacity_installation(token_id) on delete restrict,
    add column active_token_id uuid references praxis_bulk.praxis_bulk_capacity_installation(token_id) on delete restrict;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_cancel;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_atomic_attempt;
update praxis_bulk.praxis_bulk_execution e set execution_mode=p.execution_mode
from praxis_bulk.praxis_bulk_proposal p where p.proposal_id=e.proposal_id;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_atomic_attempt;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_cancel;
alter table praxis_bulk.praxis_bulk_execution alter column execution_mode set not null,
    add constraint execution_proposal_mode_fk foreign key(proposal_id,execution_mode)
        references praxis_bulk.praxis_bulk_proposal(proposal_id,execution_mode) on delete restrict,
    add constraint execution_capacity_mode_check check
      ((execution_mode='SYNC' and queue_token_id is null and active_token_id is null and status<>'QUEUED')
       or (execution_mode='ASYNC' and queue_token_id is not null and atomicity='PER_ITEM'
           and protocol_version=2 and deadline_at<=created_at+interval '30 minutes'
           and (status<>'QUEUED' or (active_token_id is null and next_ordinal=0
               and owner_epoch=1 and active_attempt_id is null and terminal_at is null
               and terminal_reason_code is null)))),
    drop constraint praxis_bulk_execution_status_check,
    add constraint praxis_bulk_execution_status_check check
      (status in ('QUEUED','RUNNING','UNIT_IN_FLIGHT','UNIT_COMMITTED_PENDING_ACK',
                  'COMPLETED','COMPLETED_WITH_ERRORS','STOPPED','RECONCILIATION_REQUIRED'));

alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_state_shape_check,
    add constraint praxis_bulk_execution_state_shape_check check (
        (status = 'QUEUED' and execution_mode='ASYNC' and next_ordinal=0 and active_attempt_id is null and terminal_at is null)
        or (status = 'RUNNING' and active_attempt_id is null and next_ordinal < target_count and terminal_at is null)
        or (status in ('UNIT_IN_FLIGHT', 'UNIT_COMMITTED_PENDING_ACK')
            and active_attempt_id is not null and terminal_at is null)
        or (status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') and active_attempt_id is null
            and next_ordinal = target_count and terminal_at is not null)
        or (status = 'STOPPED' and terminal_at is not null and active_attempt_id is null
            and active_attempt_ordinal is null and active_target_digest is null
            and active_attempt_epoch is null)
        or (status = 'RECONCILIATION_REQUIRED' and terminal_at is null));

alter table praxis_bulk.praxis_bulk_capacity_installation
    add constraint capacity_installation_token_class_key unique(token_id,capacity_class);
create table praxis_bulk.praxis_bulk_capacity_slot (
    token_id uuid primary key,
    capacity_class text not null,
    occupancy_sequence bigint not null check(occupancy_sequence>=0),
    current_execution_id uuid references praxis_bulk.praxis_bulk_execution(execution_id) on delete restrict,
    current_owner_epoch bigint,
    constraint capacity_slot_installation_fk foreign key(token_id,capacity_class)
        references praxis_bulk.praxis_bulk_capacity_installation(token_id,capacity_class) on delete restrict,
    constraint capacity_slot_shape_check check
        ((current_execution_id is null and current_owner_epoch is null)
          or (current_execution_id is not null and current_owner_epoch is not null and current_owner_epoch>=1 and occupancy_sequence>0)),
    constraint capacity_slot_execution_class_key unique(current_execution_id,capacity_class)
);
create table praxis_bulk.praxis_bulk_capacity_occupation (
    token_id uuid not null references praxis_bulk.praxis_bulk_capacity_slot(token_id) on delete restrict,
    occupancy_sequence bigint not null check(occupancy_sequence>0),
    execution_id uuid not null references praxis_bulk.praxis_bulk_execution(execution_id) on delete restrict,
    owner_epoch bigint not null check(owner_epoch>=1),
    acquired_at timestamptz not null,
    primary key(token_id,occupancy_sequence)
);
insert into praxis_bulk.praxis_bulk_capacity_slot
    (token_id,capacity_class,occupancy_sequence)
select token_id,capacity_class,0 from praxis_bulk.praxis_bulk_capacity_installation;

create function praxis_bulk.create_capacity_slot()
returns trigger language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
begin
    insert into praxis_bulk.praxis_bulk_capacity_slot(token_id,capacity_class,occupancy_sequence)
    values(new.token_id,new.capacity_class,0);
    return new;
end;
$$;
create trigger praxis_bulk_capacity_installation_create_slot after insert
on praxis_bulk.praxis_bulk_capacity_installation for each row
execute function praxis_bulk.create_capacity_slot();

create function praxis_bulk.lock_capacity_marker()
returns text language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
declare v_state text;
begin
    select state into v_state from praxis_bulk.praxis_bulk_capacity_marker where marker_id=1 for share;
    return v_state;
end;
$$;
create function praxis_bulk.capacity_marker_statement_fence()
returns trigger language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
begin
    -- Runs even for zero-row statements and deliberately has no NEW/OLD or ACTIVE gate.
    perform praxis_bulk.lock_capacity_marker();
    return null;
end;
$$;
create trigger praxis_bulk_execution_capacity_statement before insert or update
on praxis_bulk.praxis_bulk_execution for each statement
execute function praxis_bulk.capacity_marker_statement_fence();
create trigger praxis_bulk_receipt_capacity_statement before insert
on praxis_bulk.praxis_bulk_item_receipt for each statement
execute function praxis_bulk.capacity_marker_statement_fence();
create trigger praxis_bulk_admission_capacity_statement before insert
on praxis_bulk.praxis_bulk_admission for each statement
execute function praxis_bulk.capacity_marker_statement_fence();

create function praxis_bulk.guard_capacity_slot()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if TG_OP='DELETE' or current_user<>'praxis_bulk_capacity_owner' then
        raise exception 'capacity slot mutation forbidden' using errcode='55000';
    end if;
    if TG_OP='INSERT' then
        if new.occupancy_sequence<>0 or new.current_execution_id is not null then
            raise exception 'capacity slot must start free' using errcode='55000';
        end if;
    else
        if (new.token_id,new.capacity_class) is distinct from (old.token_id,old.capacity_class)
           or ((old.current_execution_id is null and new.current_execution_id is not null
                       and new.occupancy_sequence=old.occupancy_sequence+1)
               or (old.current_execution_id is not null and new.current_execution_id is null
                       and new.occupancy_sequence=old.occupancy_sequence)
               or (old.current_execution_id=new.current_execution_id
                       and new.current_execution_id is not null
                       and new.occupancy_sequence=old.occupancy_sequence
                       and new.current_owner_epoch=old.current_owner_epoch+1)) is not true then
            raise exception 'capacity slot CAS or sequence differs' using errcode='55000';
        end if;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_capacity_slot_guard before insert or update or delete
on praxis_bulk.praxis_bulk_capacity_slot for each row execute function praxis_bulk.guard_capacity_slot();
create function praxis_bulk.guard_capacity_occupation()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if TG_OP='DELETE' then
        if current_user<>'praxis_bulk_retention_owner' then
            raise exception 'capacity history deletion forbidden' using errcode='55000';
        end if;
        return old;
    end if;
    if TG_OP<>'INSERT' or current_user<>'praxis_bulk_capacity_owner' then
        raise exception 'capacity history is append-only' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_capacity_occupation_guard before insert or update or delete
on praxis_bulk.praxis_bulk_capacity_occupation for each row
execute function praxis_bulk.guard_capacity_occupation();

create function praxis_bulk.guard_capacity_execution()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
declare v_marker praxis_bulk.praxis_bulk_capacity_marker%rowtype;
begin
    if TG_OP='UPDATE' and (new.execution_mode,new.queue_token_id) is distinct from
                              (old.execution_mode,old.queue_token_id) then
        raise exception 'execution mode and queue binding are immutable' using errcode='55000';
    end if;
    if new.execution_mode='SYNC' then return new; end if;
    select * into v_marker from praxis_bulk.praxis_bulk_capacity_marker where marker_id=1;
    if not found or not exists(select 1 from praxis_bulk.praxis_bulk_namespace_binding b
          where b.namespace_id=new.namespace_id and b.deployment_id=v_marker.deployment_id)
       or not exists(select 1 from praxis_bulk.praxis_bulk_capacity_installation i
          where i.token_id=new.queue_token_id and i.capacity_class='QUEUE') then
        raise exception 'execution capacity binding differs' using errcode='55000';
    end if;
    if TG_OP='INSERT' then
        if v_marker.state<>'ACTIVE' or new.status<>'QUEUED' or new.owner_epoch<>1
           or new.next_ordinal<>0 or new.active_token_id is not null
           or new.cancel_requested_at is not null or clock_timestamp()>=new.deadline_at
           or not exists(select 1 from praxis_bulk.praxis_bulk_proposal p
              where p.proposal_id=new.proposal_id and p.execution_mode='ASYNC'
                and p.namespace_id=new.namespace_id and p.subject_id=new.subject_id
                and p.resource_key=new.resource_key and p.operation_id=new.operation_id
                and p.fingerprint=new.input_fingerprint
                and exists(select 1 from praxis_bulk.praxis_bulk_target_manifest m
                    where m.proposal_id=p.proposal_id and m.target_count=new.target_count)
                and p.protocol_version=2 and p.atomicity='PER_ITEM'
                and p.expires_at>clock_timestamp()
                and replace(pg_catalog.convert_from(p.payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->>'mode'='UNIFORM_UPDATE'
                and replace(pg_catalog.convert_from(p.payload,'UTF8'),pg_catalog.chr(92)||'u0000',pg_catalog.chr(92)||'uFFFD')::json->'intent'->'selection'->>'mode'='EXPLICIT') then
            raise exception 'asynchronous enqueue shape differs' using errcode='55000';
        end if;
    else
        if old.status='QUEUED' and new.status='RUNNING' then
            if current_user<>'praxis_bulk_capacity_owner' or v_marker.state<>'ACTIVE'
               or new.owner_epoch<>old.owner_epoch+1 or new.next_ordinal<>0
               or new.active_token_id is null or clock_timestamp()>=new.deadline_at
               or new.cancel_requested_at is not null then
                raise exception 'claim requires the protected SQL entrypoint' using errcode='55000';
            end if;
        elsif old.status='QUEUED' and new.status<>'STOPPED' then
            raise exception 'queued execution cannot enter unit or recovery state' using errcode='55000';
        elsif new.active_token_id is distinct from old.active_token_id then
            raise exception 'active capacity binding is immutable' using errcode='55000';
        end if;
        if new.status in ('UNIT_IN_FLIGHT','UNIT_COMMITTED_PENDING_ACK')
           and (v_marker.state<>'ACTIVE' or not exists(
               select 1 from praxis_bulk.praxis_bulk_capacity_slot s
               where s.token_id=new.active_token_id and s.capacity_class='ACTIVE'
                 and s.current_execution_id=new.execution_id and s.current_owner_epoch=new.owner_epoch)) then
            raise exception 'capacity executor is fenced' using errcode='55000';
        end if;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_execution_capacity_guard before insert or update
on praxis_bulk.praxis_bulk_execution for each row execute function praxis_bulk.guard_capacity_execution();
create function praxis_bulk.guard_capacity_evidence()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if exists(select 1 from praxis_bulk.praxis_bulk_execution e
       where e.execution_id=new.execution_id and e.execution_mode='ASYNC')
       and not exists(select 1 from praxis_bulk.praxis_bulk_execution e
           join praxis_bulk.praxis_bulk_capacity_slot s on s.token_id=e.active_token_id
           cross join praxis_bulk.praxis_bulk_capacity_marker m
           where e.execution_id=new.execution_id and m.marker_id=1 and m.state='ACTIVE'
             and s.capacity_class='ACTIVE' and s.current_execution_id=e.execution_id
             and s.current_owner_epoch=e.owner_epoch and new.owner_epoch=e.owner_epoch) then
        raise exception 'capacity evidence writer is fenced' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_receipt_capacity_guard before insert on praxis_bulk.praxis_bulk_item_receipt
for each row execute function praxis_bulk.guard_capacity_evidence();
create trigger praxis_bulk_admission_capacity_guard before insert on praxis_bulk.praxis_bulk_admission
for each row execute function praxis_bulk.guard_capacity_evidence();

create function praxis_bulk.materialize_capacity_execution()
returns trigger language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
declare v_slot praxis_bulk.praxis_bulk_capacity_slot%rowtype;
        v_token uuid; v_class text; v_rows integer;
begin
    if new.execution_mode<>'ASYNC' then return new; end if;
    -- All acquisition is descending from the execution row; never lock marker/control here.
    perform 1 from praxis_bulk.praxis_bulk_capacity_slot s
        where s.token_id in (new.queue_token_id,new.active_token_id) order by s.token_id for update;
    if TG_OP='INSERT' or (old.status='QUEUED' and new.status='RUNNING') then
        v_token:=case when new.status='QUEUED' then new.queue_token_id else new.active_token_id end;
        v_class:=case when new.status='QUEUED' then 'QUEUE' else 'ACTIVE' end;
        select * into v_slot from praxis_bulk.praxis_bulk_capacity_slot
            where token_id=v_token and capacity_class=v_class for update;
        if not found or v_slot.current_execution_id is not null
           or v_slot.occupancy_sequence=9223372036854775807 then
            raise exception 'capacity slot unavailable' using errcode='53300';
        end if;
        if TG_OP='UPDATE' and not exists(select 1 from praxis_bulk.praxis_bulk_capacity_slot
             where token_id=new.queue_token_id and capacity_class='QUEUE'
               and current_execution_id=new.execution_id and current_owner_epoch=old.owner_epoch) then
            raise exception 'queue occupancy is absent' using errcode='55000';
        end if;
        insert into praxis_bulk.praxis_bulk_capacity_occupation
          (token_id,occupancy_sequence,execution_id,owner_epoch,acquired_at)
        values(v_token,v_slot.occupancy_sequence+1,new.execution_id,new.owner_epoch,clock_timestamp());
        update praxis_bulk.praxis_bulk_capacity_slot
           set occupancy_sequence=occupancy_sequence+1,current_execution_id=new.execution_id,
               current_owner_epoch=new.owner_epoch
         where token_id=v_token and occupancy_sequence=v_slot.occupancy_sequence and current_execution_id is null;
        get diagnostics v_rows=row_count;
        if v_rows<>1 then raise exception 'capacity acquisition CAS lost' using errcode='55000'; end if;
        if TG_OP='INSERT' then
            update praxis_bulk.praxis_bulk_allocation set state='CONSUMED'
             where proposal_id=new.proposal_id and kind='PROPOSAL_PENDING' and state='PENDING';
            get diagnostics v_rows=row_count;
            if v_rows<>1 then raise exception 'pending proposal allocation is absent' using errcode='55000'; end if;
            insert into praxis_bulk.praxis_bulk_allocation
              (allocation_id,namespace_id,deployment_id,subject_scope_digest_version,subject_scope_digest,
               authorization_scope_digest_version,authorization_scope_digest,kind,execution_id,state,created_at)
            select new.execution_id,a.namespace_id,a.deployment_id,a.subject_scope_digest_version,a.subject_scope_digest,
                a.authorization_scope_digest_version,a.authorization_scope_digest,'EXECUTION_ASYNC',
                new.execution_id,'QUEUED',new.created_at
              from praxis_bulk.praxis_bulk_allocation a where a.proposal_id=new.proposal_id;
        else
            update praxis_bulk.praxis_bulk_capacity_slot set current_execution_id=null,current_owner_epoch=null
             where token_id=new.queue_token_id and current_execution_id=new.execution_id;
            update praxis_bulk.praxis_bulk_allocation set state='ACTIVE'
             where execution_id=new.execution_id and kind='EXECUTION_ASYNC' and state='QUEUED';
            get diagnostics v_rows=row_count;
            if v_rows<>1 then raise exception 'queued allocation is absent' using errcode='55000'; end if;
        end if;
    elsif new.status in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED') then
        update praxis_bulk.praxis_bulk_capacity_slot set current_execution_id=null,current_owner_epoch=null
         where current_execution_id=new.execution_id;
        update praxis_bulk.praxis_bulk_allocation
           set state='RELEASED',released_at=new.terminal_at,release_reason='TERMINAL_RECONCILED'
         where execution_id=new.execution_id and kind='EXECUTION_ASYNC' and state in ('QUEUED','ACTIVE');
    elsif new.owner_epoch is distinct from old.owner_epoch then
        update praxis_bulk.praxis_bulk_capacity_slot set current_owner_epoch=new.owner_epoch
         where current_execution_id=new.execution_id and current_owner_epoch=old.owner_epoch;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_execution_capacity_materialize after insert or update
on praxis_bulk.praxis_bulk_execution for each row execute function praxis_bulk.materialize_capacity_execution();

create function praxis_bulk.claim_capacity_execution(p_execution_id uuid,p_namespace_id text,
    p_worker_id text,p_active_token_id uuid,p_expected_epoch bigint)
returns boolean language plpgsql security definer set search_path=pg_catalog,pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype; v_deployment text;
    v_control record; v_allocation praxis_bulk.praxis_bulk_allocation%rowtype;
begin
    if p_worker_id is null or p_worker_id='' or p_worker_id<>btrim(p_worker_id)
       or length(p_worker_id)>200 or p_worker_id ~ '[[:cntrl:]]' then raise exception 'invalid worker identity' using errcode='22023'; end if;
    if p_execution_id is null or p_namespace_id is null or p_active_token_id is null
       or p_expected_epoch is null or p_expected_epoch<>1 then
        raise exception 'invalid claim identity or epoch' using errcode='22023';
    end if;
    if praxis_bulk.lock_capacity_marker() is distinct from 'ACTIVE' then
        raise exception 'capacity marker is not active' using errcode='55000';
    end if;
    select deployment_id into v_deployment from praxis_bulk.praxis_bulk_namespace_binding
      where namespace_id=p_namespace_id for share;
    if not found or not exists(select 1 from praxis_bulk.praxis_bulk_capacity_marker
        where marker_id=1 and deployment_id=v_deployment) then
        raise exception 'capacity namespace differs' using errcode='55000';
    end if;
    select * into v_execution from praxis_bulk.praxis_bulk_execution
      where execution_id=p_execution_id and namespace_id=p_namespace_id;
    if not found then return false; end if;
    select * into v_control from praxis_bulk.lock_operation_control(p_namespace_id,v_execution.operation_id);
    if not found or v_control.state is distinct from 'READY'
       or v_control.generation is distinct from v_execution.control_generation
       or v_control.descriptor_fingerprint is distinct from v_execution.control_descriptor_fingerprint
       or v_control.structural_revision is distinct from v_execution.structural_revision then
        raise exception 'claim control tuple differs' using errcode='55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_deployment_bucket where deployment_id=v_deployment for update;
    select * into v_allocation from praxis_bulk.praxis_bulk_allocation where execution_id=p_execution_id;
    if not found or v_allocation.deployment_id<>v_deployment or v_allocation.namespace_id<>p_namespace_id then
        raise exception 'claim allocation differs' using errcode='55000';
    end if;
    perform 1 from praxis_bulk.praxis_bulk_subject_bucket
      where deployment_id=v_deployment and subject_scope_digest_version=v_allocation.subject_scope_digest_version
        and subject_scope_digest=v_allocation.subject_scope_digest for update;
    perform 1 from praxis_bulk.praxis_bulk_proposal
      where proposal_id=v_execution.proposal_id and namespace_id=p_namespace_id
        and execution_mode='ASYNC' and control_generation=v_control.generation
        and control_descriptor_fingerprint=v_control.descriptor_fingerprint
        and control_structural_revision=v_control.structural_revision for update;
    if not found then raise exception 'claim proposal tuple differs' using errcode='55000'; end if;
    select * into v_execution from praxis_bulk.praxis_bulk_execution
      where execution_id=p_execution_id and namespace_id=p_namespace_id for update;
    if not found or v_execution.execution_mode<>'ASYNC' or v_execution.status<>'QUEUED'
       or v_execution.owner_epoch<>p_expected_epoch or v_execution.owner_epoch<>1 then return false; end if;
    if clock_timestamp()>=v_execution.deadline_at then
        update praxis_bulk.praxis_bulk_execution set status='STOPPED',terminal_reason_code='DEADLINE_EXCEEDED',
           terminal_at=clock_timestamp(),updated_at=clock_timestamp() where execution_id=p_execution_id;
        return false;
    end if;
    if not exists(select 1 from praxis_bulk.praxis_bulk_capacity_installation
           where token_id=p_active_token_id and capacity_class='ACTIVE') then
        raise exception 'active installation differs' using errcode='55000';
    end if;
    update praxis_bulk.praxis_bulk_execution set owner_id=p_worker_id,owner_epoch=owner_epoch+1,
        status='RUNNING',active_token_id=p_active_token_id,updated_at=clock_timestamp()
      where execution_id=p_execution_id and status='QUEUED' and owner_epoch=p_expected_epoch;
    return found;
end;
$$;


create or replace function praxis_bulk.guard_atomic_attempt_transition()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    -- PER_ITEM has no ATOMIC evidence gate. Do not prepare expressions referencing
    -- ATOMIC-only helpers under the minimal capacity owner during an ASYNC claim.
    if new.atomicity='PER_ITEM' then return new; end if;
    if new.atomicity='ATOMIC' and new.status='UNIT_COMMITTED_PENDING_ACK'
       and (new.next_ordinal<>0 or new.active_attempt_id is null
            or new.active_unit_deadline_at is null
            or clock_timestamp()>=new.deadline_at
            or clock_timestamp()>=new.active_unit_deadline_at
            or not praxis_bulk.atomic_evidence_complete(new.execution_id,new.target_count)) then
        raise exception 'atomic pending acknowledgement requires complete receipt' using errcode='55000';
    end if;
    if new.atomicity='ATOMIC' and new.status='COMPLETED'
       and not praxis_bulk.atomic_evidence_complete(new.execution_id,new.target_count) then
        raise exception 'atomic completion requires complete receipt' using errcode='55000';
    end if;
    if new.atomicity='ATOMIC' and new.status='STOPPED'
       and (not praxis_bulk.atomic_evidence_complete(new.execution_id,0)
            or (new.terminal_reason_code='UNIT_ROLLED_BACK' and not exists
                (select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                 where j.execution_id=new.execution_id and j.reason_code<>'DEADLINE_EXCEEDED'))
            or (new.terminal_reason_code not in ('UNIT_ROLLED_BACK','DEADLINE_EXCEEDED')
                and exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                           where j.execution_id=new.execution_id))
            or (new.terminal_reason_code='DEADLINE_EXCEEDED' and exists
                (select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                 where j.execution_id=new.execution_id and j.reason_code<>'DEADLINE_EXCEEDED'))
            or (new.terminal_reason_code='UNIT_ROLLED_BACK' and exists
                (select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                 where j.execution_id=new.execution_id and j.reason_code='DEADLINE_EXCEEDED'))) then
        raise exception 'atomic stop requires fenced absence and consistent rejection'
            using errcode='55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.release_active_allocation_on_terminal()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_released bigint;
begin
    if new.execution_mode='ASYNC' then
        if old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
           and new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
            -- The capacity materializer runs first by the attested AFTER trigger names.
            -- Certify its release; never run the SYNC local-80 release a second time.
            select count(*) into v_released from praxis_bulk.praxis_bulk_allocation a
             where a.execution_id=new.execution_id and a.namespace_id=new.namespace_id
               and a.kind='EXECUTION_ASYNC' and a.state='RELEASED'
               and a.released_at=new.terminal_at and a.release_reason='TERMINAL_RECONCILED';
            if v_released<>1
               or exists(select 1 from praxis_bulk.praxis_bulk_capacity_slot s
                         where s.current_execution_id=new.execution_id)
               or not exists(select 1 from praxis_bulk.praxis_bulk_execution e
                   join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
                   where e.execution_id=new.execution_id and e.proposal_id=new.proposal_id
                     and e.namespace_id=new.namespace_id and e.subject_id=new.subject_id
                     and e.resource_key=new.resource_key and e.operation_id=new.operation_id
                     and e.execution_mode='ASYNC' and p.execution_mode='ASYNC'
                     and e.status=new.status and e.terminal_at=new.terminal_at
                     and e.next_ordinal=new.next_ordinal and e.owner_epoch=new.owner_epoch) then
                raise exception 'terminal async execution lacks unique reconciled capacity release'
                    using errcode='55000';
            end if;
        end if;
        return null;
    end if;
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

create or replace function praxis_bulk.guard_new_bulk_admission()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_state text;
begin
    if tg_table_name = 'praxis_bulk_execution' then
        if new.status <> (case when new.execution_mode='ASYNC' then 'QUEUED' else 'RUNNING' end)
           or new.next_ordinal <> 0 or new.owner_epoch <> 1
           or new.active_attempt_id is not null or new.active_attempt_ordinal is not null
           or new.active_target_digest is not null or new.active_attempt_epoch is not null
           or new.active_unit_deadline_at is not null or new.terminal_at is not null
           or new.terminal_reason_code is not null then
            raise exception 'bulk execution insert must start in its canonical initial state' using errcode = '55000';
        end if;
    end if;
    select c.state into v_state from praxis_bulk.lock_operation_control(new.namespace_id,new.operation_id) c;
    if v_state is distinct from 'READY' then
        raise exception 'bulk operation is not ready for admission' using errcode = '55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.protect_praxis_bulk_execution_binding()
returns trigger language plpgsql as $$
begin
    if new.execution_mode is distinct from old.execution_mode
       or new.queue_token_id is distinct from old.queue_token_id
       or new.execution_id is distinct from old.execution_id
       or new.proposal_id is distinct from old.proposal_id
       or new.namespace_id is distinct from old.namespace_id
       or new.subject_id is distinct from old.subject_id
       or new.resource_key is distinct from old.resource_key
       or new.operation_id is distinct from old.operation_id
       or new.idempotency_key_digest is distinct from old.idempotency_key_digest
       or new.reservation_fingerprint is distinct from old.reservation_fingerprint
       or new.input_fingerprint is distinct from old.input_fingerprint
       or new.evaluation_fingerprint is distinct from old.evaluation_fingerprint
       or new.structural_revision is distinct from old.structural_revision
       or new.atomicity is distinct from old.atomicity
       or new.protocol_version is distinct from old.protocol_version
       or new.target_count is distinct from old.target_count
       or new.deadline_at is distinct from old.deadline_at
       or new.created_at is distinct from old.created_at then
        raise exception 'praxis_bulk.praxis_bulk_execution binding is immutable' using errcode='55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.protect_allocation_transition()
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
               or (old.state = 'ACTIVE' and new.state = 'RELEASED')
               or (old.kind='EXECUTION_ASYNC' and old.state='QUEUED' and new.state in ('ACTIVE','RELEASED'))) then
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
    if old.kind in ('EXECUTION_ACTIVE','EXECUTION_ASYNC') and new.state = 'RELEASED' then
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
    if old.kind='EXECUTION_ASYNC' and new.state='ACTIVE'
       and not exists(select 1 from praxis_bulk.praxis_bulk_execution e
           where e.execution_id=new.execution_id and e.execution_mode='ASYNC' and e.status='RUNNING'
             and exists(select 1 from praxis_bulk.praxis_bulk_capacity_slot s
                 where s.current_execution_id=e.execution_id and s.capacity_class='ACTIVE')) then
        raise exception 'async allocation requires active occupation' using errcode='55000';
    end if;
    return new;
end;
$$;

create or replace function praxis_bulk.validate_allocation_binding()
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
    if new.kind in ('EXECUTION_ACTIVE','EXECUTION_ASYNC') and not exists(
       select 1 from praxis_bulk.praxis_bulk_execution e where e.execution_id=new.execution_id
         and ((new.kind='EXECUTION_ACTIVE' and e.execution_mode='SYNC')
              or (new.kind='EXECUTION_ASYNC' and e.execution_mode='ASYNC'
                  and ((new.state='QUEUED' and e.status='QUEUED')
                       or (new.state='ACTIVE' and e.status in ('RUNNING','UNIT_IN_FLIGHT','UNIT_COMMITTED_PENDING_ACK','RECONCILIATION_REQUIRED'))
                       or (new.state='RELEASED' and e.status in ('COMPLETED','COMPLETED_WITH_ERRORS','STOPPED')))))) then
        raise exception 'allocation mode or state differs' using errcode='55000';
    end if;
    return new;
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
    perform praxis_bulk.lock_capacity_marker();
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
       or exists(select 1 from praxis_bulk.praxis_bulk_capacity_slot s where s.current_execution_id=p_execution_id)
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
    delete from praxis_bulk.praxis_bulk_capacity_occupation where execution_id=p_execution_id;
    delete from praxis_bulk.praxis_bulk_atomic_effect_ref where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_atomic_item_result where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_atomic_receipt where execution_id = p_execution_id;
    delete from praxis_bulk.praxis_bulk_atomic_rejection where execution_id = p_execution_id;
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
    perform praxis_bulk.lock_capacity_marker();
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

alter table praxis_bulk.praxis_bulk_allocation
    drop constraint praxis_bulk_allocation_shape_check,
    add constraint praxis_bulk_allocation_shape_check check
      ((kind='PROPOSAL_PENDING' and proposal_id is not null and execution_id is null
          and state in ('PENDING','CONSUMED','RELEASED'))
       or (kind='EXECUTION_ACTIVE' and execution_id is not null and proposal_id is null
          and state in ('ACTIVE','RELEASED'))
       or (kind='EXECUTION_ASYNC' and execution_id is not null and proposal_id is null
          and state in ('QUEUED','ACTIVE','RELEASED'))),
    drop constraint praxis_bulk_allocation_release_check,
    add constraint praxis_bulk_allocation_release_check check
      ((state in ('PENDING','ACTIVE','CONSUMED','QUEUED') and released_at is null and release_reason is null)
       or (kind='PROPOSAL_PENDING' and state='RELEASED' and release_reason='PROPOSAL_EXPIRED'
           and release_reason is not null and released_at is not null and released_at>=created_at)
       or (kind in ('EXECUTION_ACTIVE','EXECUTION_ASYNC') and state='RELEASED'
           and release_reason='TERMINAL_RECONCILED' and release_reason is not null
           and released_at is not null and released_at>=created_at));

-- Definer has no domain/global/install/control mutation or retention membership.
grant select on praxis_bulk.praxis_bulk_capacity_marker,praxis_bulk.praxis_bulk_capacity_installation,
    praxis_bulk.praxis_bulk_namespace_binding,praxis_bulk.praxis_bulk_proposal,
    praxis_bulk.praxis_bulk_execution,praxis_bulk.praxis_bulk_allocation,
    praxis_bulk.praxis_bulk_deployment_bucket,praxis_bulk.praxis_bulk_subject_bucket,
    praxis_bulk.praxis_bulk_capacity_slot,praxis_bulk.praxis_bulk_capacity_occupation
    to praxis_bulk_capacity_owner;
grant update(marker_id) on praxis_bulk.praxis_bulk_capacity_marker to praxis_bulk_capacity_owner;
grant update(deployment_id) on praxis_bulk.praxis_bulk_namespace_binding,
    praxis_bulk.praxis_bulk_deployment_bucket,praxis_bulk.praxis_bulk_subject_bucket to praxis_bulk_capacity_owner;
grant update(proposal_id) on praxis_bulk.praxis_bulk_proposal to praxis_bulk_capacity_owner;
grant update(status,owner_id,owner_epoch,active_token_id,updated_at,terminal_at,terminal_reason_code)
    on praxis_bulk.praxis_bulk_execution to praxis_bulk_capacity_owner;
grant insert,update on praxis_bulk.praxis_bulk_capacity_slot to praxis_bulk_capacity_owner;
grant insert on praxis_bulk.praxis_bulk_capacity_occupation to praxis_bulk_capacity_owner;
grant insert,update(state,released_at,release_reason) on praxis_bulk.praxis_bulk_allocation to praxis_bulk_capacity_owner;
grant execute on function praxis_bulk.lock_operation_control(text,text) to praxis_bulk_capacity_owner;
grant select,delete on praxis_bulk.praxis_bulk_capacity_occupation to praxis_bulk_retention_owner;
grant select on praxis_bulk.praxis_bulk_capacity_slot to praxis_bulk_retention_owner;

alter function praxis_bulk.create_capacity_slot() owner to praxis_bulk_capacity_owner;
alter function praxis_bulk.lock_capacity_marker() owner to praxis_bulk_capacity_owner;
alter function praxis_bulk.capacity_marker_statement_fence() owner to praxis_bulk_capacity_owner;
alter function praxis_bulk.materialize_capacity_execution() owner to praxis_bulk_capacity_owner;
alter function praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint) owner to praxis_bulk_capacity_owner;
revoke create on schema praxis_bulk from praxis_bulk_capacity_owner;
revoke all on praxis_bulk.praxis_bulk_capacity_slot,praxis_bulk.praxis_bulk_capacity_occupation,
    praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap from public;
revoke all on function praxis_bulk.create_capacity_slot(),praxis_bulk.lock_capacity_marker(),
    praxis_bulk.capacity_marker_statement_fence(),praxis_bulk.guard_capacity_slot(),
    praxis_bulk.guard_capacity_occupation(),praxis_bulk.guard_capacity_execution(),
    praxis_bulk.guard_capacity_evidence(),praxis_bulk.materialize_capacity_execution(),
    praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint),
    praxis_bulk.protect_capacity_occupancy_bootstrap() from public;
grant execute on function praxis_bulk.lock_capacity_marker() to praxis_bulk_retention_owner;
do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_capacity_owner from %I',current_user);
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I',current_user);
    execute pg_catalog.format('revoke praxis_bulk_control_owner from %I',current_user);
    if exists(select 1 from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r
          on r.oid=m.roleid or r.oid=m.member where r.rolname='praxis_bulk_capacity_owner') then
        raise exception 'capacity owner membership survived migration' using errcode='55000';
    end if;
end $$;
