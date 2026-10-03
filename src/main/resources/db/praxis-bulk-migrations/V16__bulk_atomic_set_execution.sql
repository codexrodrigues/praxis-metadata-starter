-- Non-rolling beta cutover. V15 producers must be drained; no old writer may admit
-- work while the two immutable atomicity/protocol bindings are installed.
do $$ begin
    perform 1 from praxis_bulk.praxis_bulk_namespace_binding order by namespace_id for share;
    perform 1 from praxis_bulk.praxis_bulk_openapi_publication order by deployment_id for update;
    perform 1 from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id for update;
    if exists (select 1 from praxis_bulk.praxis_bulk_openapi_publication
               where state='PUBLISHED' and generation=9223372036854775807)
       or exists (select 1 from praxis_bulk.praxis_bulk_operation_control
                  where state='READY' and generation=9223372036854775807) then
        raise exception 'bulk atomic cutover generation cannot advance' using errcode='22003';
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

alter table praxis_bulk.praxis_bulk_proposal
    add column atomicity text,
    add column protocol_version smallint;
-- Historical input remains byte-identical. Invalid UTF-8/JSON/missing atomicity aborts
-- rather than silently interpreting an ATOMIC proposal as PER_ITEM.
alter table praxis_bulk.praxis_bulk_proposal disable trigger praxis_bulk_proposal_reject_update;
update praxis_bulk.praxis_bulk_proposal
   set atomicity=convert_from(payload,'UTF8')::jsonb->>'atomicity',protocol_version=1;
alter table praxis_bulk.praxis_bulk_proposal enable trigger praxis_bulk_proposal_reject_update;
alter table praxis_bulk.praxis_bulk_proposal
    alter column atomicity set not null,
    alter column protocol_version set not null,
    add constraint praxis_bulk_proposal_atomicity_check check
        (atomicity in ('PER_ITEM','ATOMIC')
         and atomicity = convert_from(payload,'UTF8')::jsonb->>'atomicity'),
    add constraint praxis_bulk_proposal_protocol_check check (protocol_version in (1,2)),
    add constraint praxis_bulk_proposal_atomicity_protocol_key
        unique (proposal_id,atomicity,protocol_version);

-- An executed historical proposal could only have used the old per-item kernel.
-- A surprising binding is evidence of drift, never an upgrade inference.
do $$ begin
    if exists (select 1 from praxis_bulk.praxis_bulk_execution e
               join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
               where p.atomicity<>'PER_ITEM') then
        raise exception 'historical atomic execution cannot be reinterpreted' using errcode='55000';
    end if;
end $$;
alter table praxis_bulk.praxis_bulk_execution
    add column atomicity text,
    add column protocol_version smallint,
    add column active_set_digest text;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_cancel;
update praxis_bulk.praxis_bulk_execution set atomicity='PER_ITEM',protocol_version=1;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_protect_cancel;
alter table praxis_bulk.praxis_bulk_execution
    alter column atomicity set not null,
    alter column protocol_version set not null,
    add constraint praxis_bulk_execution_atomicity_check check (atomicity in ('PER_ITEM','ATOMIC')),
    add constraint praxis_bulk_execution_protocol_check check (protocol_version in (1,2)),
    add constraint praxis_bulk_execution_atomicity_protocol_fkey
        foreign key (proposal_id,atomicity,protocol_version)
        references praxis_bulk.praxis_bulk_proposal(proposal_id,atomicity,protocol_version);

-- New writes must name the protocol and atomicity. Old wire/storage clients cannot
-- smuggle a version-one row into the post-cutover ledger.
create function praxis_bulk.guard_bulk_protocol_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if new.protocol_version is distinct from 2 then
        raise exception 'new bulk rows require protocol version two' using errcode='55000';
    end if;
    if tg_table_name='praxis_bulk_execution' then
        if new.active_set_digest is not null then
            raise exception 'new bulk execution cannot start with an active set' using errcode='55000';
        end if;
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_proposal_protocol_insert before insert on praxis_bulk.praxis_bulk_proposal
    for each row execute function praxis_bulk.guard_bulk_protocol_insert();
create trigger praxis_bulk_execution_protocol_insert before insert on praxis_bulk.praxis_bulk_execution
    for each row execute function praxis_bulk.guard_bulk_protocol_insert();

alter table praxis_bulk.praxis_bulk_execution
    drop constraint praxis_bulk_execution_attempt_shape_check;
alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_attempt_shape_check check (
        (atomicity='PER_ITEM' and active_set_digest is null
         and ((active_attempt_id is null) = (active_attempt_ordinal is null))
         and ((active_attempt_id is null) = (active_target_digest is null))
         and ((active_attempt_id is null) = (active_attempt_epoch is null))
         and (active_attempt_id is null or
              (active_attempt_ordinal=next_ordinal
               and active_attempt_ordinal between 0 and target_count-1
               and active_attempt_epoch between 1 and owner_epoch
               and active_target_digest ~ '^sha256:[0-9a-f]{64}$')))
        or (atomicity='ATOMIC' and target_count between 1 and 50
            and next_ordinal in (0,target_count)
            and active_attempt_ordinal is null and active_target_digest is null
            and ((active_attempt_id is null) = (active_attempt_epoch is null))
            and ((active_attempt_id is null) = (active_set_digest is null))
            and (active_attempt_id is null or
                 (next_ordinal=0 and active_attempt_epoch between 1 and owner_epoch
                  and active_set_digest ~ '^sha256:[0-9a-f]{64}$')))
    );

-- A header is the only receipt of an ATOMIC attempt. Children are durable
-- projections and cannot be inserted independently or shown before ACK.
create table praxis_bulk.praxis_bulk_atomic_receipt (
    execution_id uuid primary key references praxis_bulk.praxis_bulk_execution(execution_id),
    attempt_id uuid not null unique,
    owner_epoch bigint not null check (owner_epoch>=1),
    set_digest text not null check (set_digest ~ '^sha256:[0-9a-f]{64}$'),
    target_count integer not null check (target_count between 1 and 50),
    effect_count integer not null check (effect_count between 0 and 400),
    effect_digest text not null check (effect_digest ~ '^sha256:[0-9a-f]{64}$'),
    confirmed_at timestamptz not null,
    unit_deadline_at timestamptz not null,
    constraint praxis_bulk_atomic_receipt_time_check check (confirmed_at < unit_deadline_at)
);
create table praxis_bulk.praxis_bulk_atomic_item_result (
    execution_id uuid not null references praxis_bulk.praxis_bulk_atomic_receipt(execution_id),
    unit_ordinal integer not null check (unit_ordinal between 0 and 49),
    target_digest text not null check (target_digest ~ '^sha256:[0-9a-f]{64}$'),
    expected_version text not null check (btrim(expected_version)<>''),
    outcome text not null check (outcome in ('CONFIRMED','UNCHANGED')),
    constraint praxis_bulk_atomic_item_result_pkey primary key (execution_id,unit_ordinal),
    constraint praxis_bulk_atomic_item_result_target_key unique (execution_id,target_digest)
);
create table praxis_bulk.praxis_bulk_atomic_effect_ref (
    execution_id uuid not null,
    unit_ordinal integer not null,
    effect_ref text not null check (length(effect_ref) between 1 and 200
        and effect_ref=btrim(effect_ref,concat(
            chr(32),chr(5760),chr(8192),chr(8193),chr(8194),chr(8195),
            chr(8196),chr(8197),chr(8198),chr(8200),chr(8201),chr(8202),
            chr(8232),chr(8233),chr(8287),chr(12288)))
        and effect_ref=translate(effect_ref,concat(
            chr(1),chr(2),chr(3),chr(4),chr(5),chr(6),chr(7),chr(8),
            chr(9),chr(10),chr(11),chr(12),chr(13),chr(14),chr(15),chr(16),
            chr(17),chr(18),chr(19),chr(20),chr(21),chr(22),chr(23),chr(24),
            chr(25),chr(26),chr(27),chr(28),chr(29),chr(30),chr(31),chr(127),
            chr(128),chr(129),chr(130),chr(131),chr(132),chr(133),chr(134),chr(135),
            chr(136),chr(137),chr(138),chr(139),chr(140),chr(141),chr(142),chr(143),
            chr(144),chr(145),chr(146),chr(147),chr(148),chr(149),chr(150),chr(151),
            chr(152),chr(153),chr(154),chr(155),chr(156),chr(157),chr(158),chr(159)),'')),
    constraint praxis_bulk_atomic_effect_ref_pkey primary key (execution_id,effect_ref),
    constraint praxis_bulk_atomic_effect_ref_item_fkey foreign key (execution_id,unit_ordinal)
        references praxis_bulk.praxis_bulk_atomic_item_result(execution_id,unit_ordinal)
);
-- A whole-set denial has no receipt and no per-target admission row. Its closed
-- reason is retained separately from the common STOPPED code without target IDs.
create table praxis_bulk.praxis_bulk_atomic_rejection (
    execution_id uuid primary key references praxis_bulk.praxis_bulk_execution(execution_id),
    attempt_id uuid not null unique,
    set_digest text not null check (set_digest ~ '^sha256:[0-9a-f]{64}$'),
    reason_code text not null check (reason_code in (
        'TARGET_VERSION_CONFLICT','TARGET_STATE_CONFLICT','TARGET_NOT_FOUND','TARGET_DENIED',
        'TARGET_INVALID','TARGET_DEPENDENCY_CHANGED','DEADLINE_EXCEEDED',
        'AUTHORIZATION_REVOKED','POLICY_BLOCKED','COMMON_GOVERNANCE_CHANGED',
        'COMMON_GOVERNANCE_UNAVAILABLE','DEPENDENCY_UNAVAILABLE','UNIT_ROLLED_BACK',
        'RECOVERY_STOPPED','EVALUATOR_UNAVAILABLE','STRUCTURAL_REVISION_CHANGED')),
    recorded_at timestamptz not null
);
-- Flyway commits DDL before the explicit Java role bootstrap. The marker makes
-- that grant step retryable without repairing a completed deployment silently.
create table praxis_bulk.praxis_bulk_atomic_bootstrap (
    bootstrap_version integer primary key check (bootstrap_version=16),
    phase text not null check (phase in ('PENDING','COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_atomic_bootstrap values (16,'PENDING');
revoke all on praxis_bulk.praxis_bulk_atomic_bootstrap from public;

create function praxis_bulk.guard_atomic_receipt_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
     where e.execution_id=new.execution_id for update;
    if not found or v_execution.atomicity<>'ATOMIC'
       or v_execution.status<>'UNIT_IN_FLIGHT' or v_execution.next_ordinal<>0
       or new.attempt_id is distinct from v_execution.active_attempt_id
       or new.owner_epoch is distinct from v_execution.active_attempt_epoch
       or new.owner_epoch is distinct from v_execution.owner_epoch
       or new.set_digest is distinct from v_execution.active_set_digest
       or new.target_count is distinct from v_execution.target_count
       or new.unit_deadline_at is distinct from v_execution.active_unit_deadline_at
       or new.confirmed_at >= v_execution.deadline_at then
        raise exception 'atomic receipt differs from the fenced set attempt' using errcode='55000';
    end if;
    if exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
              where j.execution_id=new.execution_id) then
        raise exception 'atomic receipt cannot follow a rejection' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_atomic_receipt_guard_insert
before insert on praxis_bulk.praxis_bulk_atomic_receipt
for each row execute function praxis_bulk.guard_atomic_receipt_insert();
create function praxis_bulk.guard_atomic_item_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
     where e.execution_id=new.execution_id for update;
    if not found or v_execution.atomicity<>'ATOMIC'
       or v_execution.status<>'UNIT_IN_FLIGHT' or v_execution.next_ordinal<>0
       or new.unit_ordinal>=v_execution.target_count
       or not exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                     where h.execution_id=new.execution_id
                       and h.attempt_id=v_execution.active_attempt_id
                       and h.owner_epoch=v_execution.active_attempt_epoch
                       and h.set_digest=v_execution.active_set_digest)
       or not exists(select 1 from praxis_bulk.praxis_bulk_target_manifest m
                     where m.proposal_id=v_execution.proposal_id
                       and m.ordinal=new.unit_ordinal
                       and m.target_digest=new.target_digest
                       and convert_from(m.expected_version,'UTF8')=new.expected_version) then
        raise exception 'atomic item differs from the active set' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_atomic_item_result_guard_insert before insert
on praxis_bulk.praxis_bulk_atomic_item_result for each row
execute function praxis_bulk.guard_atomic_item_insert();
create function praxis_bulk.guard_atomic_effect_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
     where e.execution_id=new.execution_id for update;
    if not found or v_execution.atomicity<>'ATOMIC'
       or v_execution.status<>'UNIT_IN_FLIGHT' or v_execution.next_ordinal<>0
       or not exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                     where h.execution_id=new.execution_id
                       and h.attempt_id=v_execution.active_attempt_id
                       and h.owner_epoch=v_execution.active_attempt_epoch
                       and h.set_digest=v_execution.active_set_digest)
       or not exists(select 1 from praxis_bulk.praxis_bulk_atomic_item_result i
                     where i.execution_id=new.execution_id and i.unit_ordinal=new.unit_ordinal
                       and i.outcome='CONFIRMED')
       or (select count(*) from praxis_bulk.praxis_bulk_atomic_effect_ref f
           where f.execution_id=new.execution_id and f.unit_ordinal=new.unit_ordinal)>=8
       or (select count(*) from praxis_bulk.praxis_bulk_atomic_effect_ref f
           where f.execution_id=new.execution_id)>=400 then
        raise exception 'atomic effect differs from the active set' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_atomic_effect_ref_guard_insert before insert
on praxis_bulk.praxis_bulk_atomic_effect_ref for each row
execute function praxis_bulk.guard_atomic_effect_insert();
create function praxis_bulk.guard_atomic_rejection_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
declare v_execution praxis_bulk.praxis_bulk_execution%rowtype;
begin
    select e.* into v_execution from praxis_bulk.praxis_bulk_execution e
     where e.execution_id=new.execution_id for update;
    if not found or v_execution.atomicity<>'ATOMIC'
       or v_execution.status<>'UNIT_IN_FLIGHT' or v_execution.next_ordinal<>0
       or new.attempt_id is distinct from v_execution.active_attempt_id
       or new.set_digest is distinct from v_execution.active_set_digest
       or exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                 where h.execution_id=new.execution_id) then
        raise exception 'atomic rejection differs from the fenced set attempt' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_atomic_rejection_guard_insert before insert
on praxis_bulk.praxis_bulk_atomic_rejection for each row
execute function praxis_bulk.guard_atomic_rejection_insert();
create function praxis_bulk.reject_atomic_evidence_mutation()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    raise exception 'atomic evidence is immutable' using errcode='55000';
end;
$$;
create trigger praxis_bulk_atomic_receipt_reject_mutation before update
on praxis_bulk.praxis_bulk_atomic_receipt for each row
execute function praxis_bulk.reject_atomic_evidence_mutation();
create trigger praxis_bulk_atomic_item_result_reject_mutation before update
on praxis_bulk.praxis_bulk_atomic_item_result for each row
execute function praxis_bulk.reject_atomic_evidence_mutation();
create trigger praxis_bulk_atomic_effect_ref_reject_mutation before update
on praxis_bulk.praxis_bulk_atomic_effect_ref for each row
execute function praxis_bulk.reject_atomic_evidence_mutation();
create trigger praxis_bulk_atomic_receipt_guard_delete before delete
on praxis_bulk.praxis_bulk_atomic_receipt for each row
execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_atomic_item_result_guard_delete before delete
on praxis_bulk.praxis_bulk_atomic_item_result for each row
execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_atomic_effect_ref_guard_delete before delete
on praxis_bulk.praxis_bulk_atomic_effect_ref for each row
execute function praxis_bulk.guard_lifecycle_delete();
create trigger praxis_bulk_atomic_rejection_reject_mutation before update
on praxis_bulk.praxis_bulk_atomic_rejection for each row
execute function praxis_bulk.reject_atomic_evidence_mutation();
create trigger praxis_bulk_atomic_rejection_guard_delete before delete
on praxis_bulk.praxis_bulk_atomic_rejection for each row
execute function praxis_bulk.guard_lifecycle_delete();

create function praxis_bulk.atomic_evidence_complete(p_execution_id uuid,p_required_count integer)
returns boolean language sql stable set search_path=pg_catalog,pg_temp as $$
    select e.atomicity='ATOMIC' and e.target_count between 1 and 50
       and not exists(select 1 from praxis_bulk.praxis_bulk_item_receipt r
                      where r.execution_id=e.execution_id)
       and not exists(select 1 from praxis_bulk.praxis_bulk_admission a
                      where a.execution_id=e.execution_id)
       and ((p_required_count=0 and e.next_ordinal=0
             and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                            where h.execution_id=e.execution_id)
             and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_item_result i
                            where i.execution_id=e.execution_id))
            or (p_required_count=e.target_count
                and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                               where j.execution_id=e.execution_id)
                and exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                           where h.execution_id=e.execution_id and h.target_count=e.target_count
                             and h.owner_epoch<=e.owner_epoch and h.confirmed_at<e.deadline_at
                             and h.confirmed_at<h.unit_deadline_at
                             and h.effect_count=(select count(*) from praxis_bulk.praxis_bulk_atomic_effect_ref f
                                                 where f.execution_id=e.execution_id)
                             and h.effect_digest='sha256:' || pg_catalog.encode(pg_catalog.sha256(
                                 pg_catalog.int4send(pg_catalog.octet_length(
                                     pg_catalog.convert_to('praxis.bulk.atomic-effects/1','UTF8')))
                                 || pg_catalog.convert_to('praxis.bulk.atomic-effects/1','UTF8')
                                 || pg_catalog.int4send(pg_catalog.octet_length(
                                     pg_catalog.convert_to(h.effect_count::text,'UTF8')))
                                 || pg_catalog.convert_to(h.effect_count::text,'UTF8')
                                 || coalesce((select pg_catalog.string_agg(
                                     pg_catalog.int4send(pg_catalog.octet_length(
                                         pg_catalog.convert_to(f.unit_ordinal::text,'UTF8')))
                                     || pg_catalog.convert_to(f.unit_ordinal::text,'UTF8')
                                     || pg_catalog.int4send(pg_catalog.octet_length(
                                         pg_catalog.convert_to(f.effect_ref,'UTF8')))
                                     || pg_catalog.convert_to(f.effect_ref,'UTF8'),''::bytea
                                     order by f.unit_ordinal,f.effect_ref collate "C")
                                    from praxis_bulk.praxis_bulk_atomic_effect_ref f
                                    where f.execution_id=e.execution_id),''::bytea)), 'hex')
                             and h.set_digest ~ '^sha256:[0-9a-f]{64}$')
                and (select count(*) from praxis_bulk.praxis_bulk_atomic_item_result i
                     where i.execution_id=e.execution_id)=e.target_count
                and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_effect_ref f
                               join praxis_bulk.praxis_bulk_atomic_item_result i
                                 on i.execution_id=f.execution_id and i.unit_ordinal=f.unit_ordinal
                               where f.execution_id=e.execution_id and i.outcome='UNCHANGED')
                and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_effect_ref f
                               where f.execution_id=e.execution_id
                               group by f.unit_ordinal having count(*)>8)
                and not exists(
                    select 1 from praxis_bulk.praxis_bulk_target_manifest m
                    left join praxis_bulk.praxis_bulk_atomic_item_result i
                      on i.execution_id=e.execution_id and i.unit_ordinal=m.ordinal
                    where m.proposal_id=e.proposal_id
                      and (i.unit_ordinal is null or i.target_digest<>m.target_digest
                           or i.expected_version<>convert_from(m.expected_version,'UTF8')))
                and (select count(*) from praxis_bulk.praxis_bulk_target_manifest m
                     where m.proposal_id=e.proposal_id)=e.target_count))
    from praxis_bulk.praxis_bulk_execution e where e.execution_id=p_execution_id;
$$;

-- Existing per-item evidence retains the historical exact ordinal union; the
-- ATOMIC branch recognizes only one header plus complete ordered child projections.
create or replace function praxis_bulk.terminal_evidence_complete(p_execution_id uuid,p_required_count integer)
returns boolean language sql stable set search_path=pg_catalog,pg_temp as $$
    select case when e.atomicity='ATOMIC' then
        praxis_bulk.atomic_evidence_complete(p_execution_id,p_required_count)
        and (e.status<>'STOPPED' or
             (e.terminal_reason_code='UNIT_ROLLED_BACK'
              and exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                         where j.execution_id=e.execution_id
                           and j.reason_code<>'DEADLINE_EXCEEDED')
              or e.terminal_reason_code='DEADLINE_EXCEEDED'
                 and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                                where j.execution_id=e.execution_id
                                  and j.reason_code<>'DEADLINE_EXCEEDED')
              or e.terminal_reason_code not in ('UNIT_ROLLED_BACK','DEADLINE_EXCEEDED')
                 and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_rejection j
                                where j.execution_id=e.execution_id)))
    else
       p_required_count>=0
       and not exists (
           select 1 from praxis_bulk.praxis_bulk_item_receipt r
           join praxis_bulk.praxis_bulk_admission a on a.attempt_id=r.attempt_id
           where r.execution_id=p_execution_id or a.execution_id=p_execution_id)
       and (select count(*) from (
            select r.unit_ordinal from praxis_bulk.praxis_bulk_item_receipt r
              where r.execution_id=p_execution_id
            union all
            select a.unit_ordinal from praxis_bulk.praxis_bulk_admission a
              where a.execution_id=p_execution_id) x)=p_required_count
       and not exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt h
                      where h.execution_id=e.execution_id)
       and (select count(distinct unit_ordinal) from (
            select r.unit_ordinal from praxis_bulk.praxis_bulk_item_receipt r
              where r.execution_id=p_execution_id
            union all
            select a.unit_ordinal from praxis_bulk.praxis_bulk_admission a
              where a.execution_id=p_execution_id) x)=p_required_count
       and (select count(distinct target_digest) from (
            select r.target_digest from praxis_bulk.praxis_bulk_item_receipt r
              where r.execution_id=p_execution_id
            union all
            select a.target_digest from praxis_bulk.praxis_bulk_admission a
              where a.execution_id=p_execution_id) x)=p_required_count
       and (select count(distinct attempt_id) from (
            select r.attempt_id from praxis_bulk.praxis_bulk_item_receipt r
              where r.execution_id=p_execution_id
            union all
            select a.attempt_id from praxis_bulk.praxis_bulk_admission a
              where a.execution_id=p_execution_id) x)=p_required_count
       and coalesce((select max(owner_epoch) from (
            select r.owner_epoch from praxis_bulk.praxis_bulk_item_receipt r
              where r.execution_id=p_execution_id
            union all
            select a.owner_epoch from praxis_bulk.praxis_bulk_admission a
              where a.execution_id=p_execution_id) x),0)<=e.owner_epoch
       and (p_required_count=0 or
            ((select min(unit_ordinal) from (
                select r.unit_ordinal from praxis_bulk.praxis_bulk_item_receipt r
                  where r.execution_id=p_execution_id
                union all
                select a.unit_ordinal from praxis_bulk.praxis_bulk_admission a
                  where a.execution_id=p_execution_id) x)=0
             and (select max(unit_ordinal) from (
                select r.unit_ordinal from praxis_bulk.praxis_bulk_item_receipt r
                  where r.execution_id=p_execution_id
                union all
                select a.unit_ordinal from praxis_bulk.praxis_bulk_admission a
                  where a.execution_id=p_execution_id) x)=p_required_count-1))
    end from praxis_bulk.praxis_bulk_execution e where e.execution_id=p_execution_id;
$$;

alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_atomic_terminal_check check
      (atomicity<>'ATOMIC' or status<>'COMPLETED_WITH_ERRORS');

-- Receipt insertion precedes children, but the pending-ACK transition must not
-- commit until the whole indexed set exists. A direct per-item writer is fenced.
create function praxis_bulk.guard_atomic_attempt_transition()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
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
create trigger praxis_bulk_execution_guard_atomic_attempt before update
on praxis_bulk.praxis_bulk_execution for each row
execute function praxis_bulk.guard_atomic_attempt_transition();
create function praxis_bulk.guard_per_item_evidence_insert()
returns trigger language plpgsql set search_path=pg_catalog,pg_temp as $$
begin
    if not exists(select 1 from praxis_bulk.praxis_bulk_execution e
                  where e.execution_id=new.execution_id and e.atomicity='PER_ITEM'
                    and e.protocol_version=2) then
        raise exception 'per-item evidence cannot bind an atomic execution' using errcode='55000';
    end if;
    return new;
end;
$$;
create trigger praxis_bulk_receipt_per_item_only before insert
on praxis_bulk.praxis_bulk_item_receipt for each row
execute function praxis_bulk.guard_per_item_evidence_insert();
create trigger praxis_bulk_admission_per_item_only before insert
on praxis_bulk.praxis_bulk_admission for each row
execute function praxis_bulk.guard_per_item_evidence_insert();

create or replace function praxis_bulk.protect_praxis_bulk_execution_binding()
returns trigger language plpgsql as $$
begin
    if new.execution_id is distinct from old.execution_id
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

revoke all on function praxis_bulk.guard_bulk_protocol_insert(),
    praxis_bulk.guard_atomic_receipt_insert(),
    praxis_bulk.guard_atomic_item_insert(),
    praxis_bulk.guard_atomic_effect_insert(),
    praxis_bulk.guard_atomic_rejection_insert(),
    praxis_bulk.reject_atomic_evidence_mutation(),
    praxis_bulk.atomic_evidence_complete(uuid,integer),
    praxis_bulk.guard_atomic_attempt_transition(),
    praxis_bulk.guard_per_item_evidence_insert() from public;
grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer)
    to praxis_bulk_retention_owner;

-- Replacing the existing SECURITY DEFINER retention functions keeps their
-- dedicated owner. Membership is temporary and revoked before migration commit.
do $$ begin
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I',current_user);
end $$;

revoke all on praxis_bulk.praxis_bulk_atomic_receipt,
    praxis_bulk.praxis_bulk_atomic_item_result,
    praxis_bulk.praxis_bulk_atomic_effect_ref,
    praxis_bulk.praxis_bulk_atomic_rejection from public;
-- Host grants runtime SELECT/INSERT explicitly after migration. Retention may only
-- DELETE via its SECURITY DEFINER purge after terminal evidence validation.
grant select,delete on praxis_bulk.praxis_bulk_atomic_receipt,
    praxis_bulk.praxis_bulk_atomic_item_result,
    praxis_bulk.praxis_bulk_atomic_effect_ref,
    praxis_bulk.praxis_bulk_atomic_rejection to praxis_bulk_retention_owner;

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


do $$ begin
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I',current_user);
    if exists(select 1 from pg_catalog.pg_auth_members m
              where m.roleid='praxis_bulk_retention_owner'::pg_catalog.regrole) then
        raise exception 'bulk retention-owner membership survived atomic migration' using errcode='55000';
    end if;
end $$;
