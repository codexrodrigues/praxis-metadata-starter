-- V13: certify the V5 terminal guard, V10 cancellation guard and six execution
-- UPDATE trigger bindings before replacing the V5 timestamp block.
-- Flyway executes this PostgreSQL migration transactionally; failed attestation
-- aborts before any role or row change.
do $$
declare
    v_function oid;
    v_cancel_function oid;
begin
    select p.oid into v_function
    from pg_catalog.pg_proc p
    join pg_catalog.pg_namespace n on n.oid=p.pronamespace
    join pg_catalog.pg_roles owner on owner.oid=p.proowner
    join pg_catalog.pg_language lang on lang.oid=p.prolang
    where n.nspname='praxis_bulk' and p.proname='guard_terminal_execution'
      and pg_catalog.pg_get_function_identity_arguments(p.oid)=''
      and owner.rolname='praxis_bulk_retention_owner'
      and lang.lanname='plpgsql' and p.prorettype='trigger'::pg_catalog.regtype
      and p.prosecdef and p.provolatile='v' and p.prokind='f'
      and not p.proleakproof and p.proparallel='u'
      and p.proconfig = array['search_path=pg_catalog, pg_temp']::text[]
      and pg_catalog.md5(p.prosrc)='d5355ce55cb68977645243ca86d4b126';
    select p.oid into v_cancel_function
    from pg_catalog.pg_proc p
    join pg_catalog.pg_namespace n on n.oid=p.pronamespace
    join pg_catalog.pg_language lang on lang.oid=p.prolang
    where n.nspname='praxis_bulk' and p.proname='protect_cancel_request'
      and pg_catalog.pg_get_function_identity_arguments(p.oid)=''
      and p.proowner=n.nspowner
      and lang.lanname='plpgsql' and p.prorettype='trigger'::pg_catalog.regtype
      and not p.prosecdef and p.provolatile='v' and p.prokind='f'
      and not p.proleakproof and p.proparallel='u'
      and p.proconfig = array['search_path=pg_catalog, pg_temp']::text[]
      and pg_catalog.md5(p.prosrc)='0d1fcbb4c987ad430db4bcaaed13b50e';
    if v_function is null
       or v_cancel_function is null
       or pg_catalog.has_schema_privilege('praxis_bulk_retention_owner', 'praxis_bulk', 'CREATE')
       or exists (select 1 from pg_catalog.pg_auth_members m
                  where m.roleid='praxis_bulk_retention_owner'::pg_catalog.regrole)
       or exists (select 1 from pg_catalog.aclexplode(
                    (select coalesce(p.proacl,
                        pg_catalog.acldefault('f', p.proowner))
                     from pg_catalog.pg_proc p where p.oid=v_function)) acl
                  where acl.privilege_type='EXECUTE' and acl.grantee <> (
                      select p.proowner from pg_catalog.pg_proc p where p.oid=v_function))
       or exists (select 1 from pg_catalog.aclexplode(
                    (select coalesce(p.proacl,
                        pg_catalog.acldefault('f', p.proowner))
                     from pg_catalog.pg_proc p where p.oid=v_cancel_function)) acl
                  where acl.privilege_type='EXECUTE' and acl.grantee <> (
                      select p.proowner from pg_catalog.pg_proc p where p.oid=v_cancel_function))
       or not exists (select 1 from pg_catalog.pg_trigger t
                      where t.tgrelid='praxis_bulk.praxis_bulk_execution'::pg_catalog.regclass
                        and t.tgname='praxis_bulk_execution_guard_terminal'
                        and t.tgfoid=v_function and t.tgtype=19
                        and t.tgqual is null and t.tgnargs=0
                        and t.tgattr = ''::pg_catalog.int2vector and pg_catalog.octet_length(t.tgargs)=0
                        and t.tgoldtable is null and t.tgnewtable is null
                        and t.tgenabled='O' and not t.tgisinternal)
       or not exists (select 1 from pg_catalog.pg_trigger t
                      where t.tgrelid='praxis_bulk.praxis_bulk_execution'::pg_catalog.regclass
                        and t.tgname='praxis_bulk_execution_protect_cancel'
                        and t.tgfoid=v_cancel_function and t.tgtype=23
                        and t.tgqual is null and t.tgnargs=0
                        and t.tgattr = ''::pg_catalog.int2vector and pg_catalog.octet_length(t.tgargs)=0
                        and t.tgoldtable is null and t.tgnewtable is null
                        and t.tgenabled='O' and not t.tgisinternal)
       or (select count(*) from pg_catalog.pg_trigger t
           where t.tgrelid='praxis_bulk.praxis_bulk_execution'::pg_catalog.regclass
             and (t.tgtype::integer & 16) <> 0 and not t.tgisinternal) <> 6
       or exists (
           select 1 from (values
               ('praxis_bulk_execution_protect_binding', 'protect_praxis_bulk_execution_binding', 19),
               ('praxis_bulk_execution_protect_terminal_reason', 'protect_praxis_bulk_terminal_reason', 23),
               ('praxis_bulk_execution_guard_terminal', 'guard_terminal_execution', 19),
               ('praxis_bulk_execution_release_active_allocation', 'release_active_allocation_on_terminal', 17),
               ('praxis_bulk_execution_protect_descriptor_binding', 'protect_execution_descriptor_binding', 19),
               ('praxis_bulk_execution_protect_cancel', 'protect_cancel_request', 23)
           ) expected(trigger_name, function_name, trigger_type)
           left join pg_catalog.pg_trigger t
             on t.tgrelid='praxis_bulk.praxis_bulk_execution'::pg_catalog.regclass
            and t.tgname=expected.trigger_name and not t.tgisinternal
           where t.oid is null or t.tgfoid is distinct from
                 pg_catalog.to_regprocedure('praxis_bulk.' || expected.function_name || '()')
              or t.tgtype <> expected.trigger_type or t.tgenabled <> 'O'
              or t.tgqual is not null or t.tgnargs <> 0
              or t.tgattr <> ''::pg_catalog.int2vector or pg_catalog.octet_length(t.tgargs) <> 0
              or t.tgoldtable is not null or t.tgnewtable is not null)
    then
        raise exception 'bulk V5/V10 chronology guard attestation failed' using errcode='55000';
    end if;
    execute pg_catalog.format('grant praxis_bulk_retention_owner to %I', current_user);
    execute pg_catalog.format(
        'alter function praxis_bulk.guard_terminal_execution() owner to %I', current_user);
end;
$$;

create or replace function praxis_bulk.guard_terminal_execution()
returns trigger language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
    v_admission_count bigint;
begin
    if old.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and new is distinct from old then
        raise exception 'bulk terminal execution is fenced and immutable' using errcode = '55000';
    end if;
    if old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED') then
        -- Retention starts at the database-observed transition, never at a caller
        -- supplied timestamp that could make a fresh result immediately purgeable.
        new.terminal_at := greatest(clock_timestamp(), old.updated_at, old.cancel_requested_at);
        new.updated_at := greatest(new.updated_at, new.terminal_at);
    end if;
    if new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
       and (old.status not in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'STOPPED')
            or new.status is distinct from old.status) then
        if new.active_attempt_id is not null or new.owner_epoch < 1
           or new.owner_epoch not in (old.owner_epoch, old.owner_epoch + 1)
           or new.next_ordinal < old.next_ordinal
           or new.terminal_at is null
           or (new.status = 'STOPPED' and
               (new.terminal_reason_code is null or
                not praxis_bulk.terminal_evidence_complete(new.execution_id, new.next_ordinal)))
           or (new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') and
               (new.next_ordinal <> new.target_count or
                not praxis_bulk.terminal_evidence_complete(new.execution_id, new.target_count))) then
            raise exception 'bulk execution lacks terminal evidence or fencing' using errcode = '55000';
        end if;
        if new.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS') then
            select count(*) into v_admission_count
            from praxis_bulk.praxis_bulk_admission a where a.execution_id = new.execution_id;
            if (new.status = 'COMPLETED' and v_admission_count <> 0)
               or (new.status = 'COMPLETED_WITH_ERRORS' and v_admission_count = 0) then
                raise exception 'bulk execution terminal status does not match unit evidence' using errcode = '55000';
            end if;
        end if;
    end if;
    return new;
end;
$$;

-- Restore the governed function owner and zero-membership boundary before commit.
grant create on schema praxis_bulk to praxis_bulk_retention_owner;
do $$
begin
    alter function praxis_bulk.guard_terminal_execution() owner to praxis_bulk_retention_owner;
    execute pg_catalog.format('revoke praxis_bulk_retention_owner from %I', current_user);
    if exists (select 1 from pg_catalog.pg_auth_members m
               where m.roleid='praxis_bulk_retention_owner'::pg_catalog.regrole) then
        raise exception 'bulk retention owner membership was not fully revoked' using errcode='55000';
    end if;
end;
$$;
revoke create on schema praxis_bulk from praxis_bulk_retention_owner;
do $$
begin
    if pg_catalog.has_schema_privilege('praxis_bulk_retention_owner', 'praxis_bulk', 'CREATE') then
        raise exception 'bulk retention owner retains schema CREATE' using errcode='55000';
    end if;
end;
$$;

-- The repair changes only updated_at on already-terminal rows. Under the
-- transaction's ACCESS EXCLUSIVE table lock, no UPDATE trigger needs to observe
-- this historical correction; restore every certified trigger before commit.
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution
    disable trigger praxis_bulk_execution_protect_cancel;
update praxis_bulk.praxis_bulk_execution
   set updated_at=terminal_at
 where terminal_at is not null and terminal_at > updated_at;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_protect_binding;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_protect_terminal_reason;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_guard_terminal;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_release_active_allocation;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_protect_descriptor_binding;
alter table praxis_bulk.praxis_bulk_execution
    enable trigger praxis_bulk_execution_protect_cancel;
do $$
begin
    if (select count(*) from pg_catalog.pg_trigger t
        where t.tgrelid='praxis_bulk.praxis_bulk_execution'::pg_catalog.regclass
          and (t.tgtype::integer & 16) <> 0 and not t.tgisinternal
          and t.tgenabled='O') <> 6 then
        raise exception 'bulk execution UPDATE triggers were not restored' using errcode='55000';
    end if;
end;
$$;

-- Any other impossible historical chronology aborts the upgrade; no row is silently
-- normalized by a reader. Future writes are guarded by PostgreSQL itself.
alter table praxis_bulk.praxis_bulk_execution
    add constraint praxis_bulk_execution_time_order_check check (
        created_at <= updated_at
        and (terminal_at is null or
             (created_at <= terminal_at and terminal_at <= updated_at))
        and (cancel_requested_at is null or
             (created_at <= cancel_requested_at and cancel_requested_at <= updated_at
              and (terminal_at is null or cancel_requested_at <= terminal_at))));
