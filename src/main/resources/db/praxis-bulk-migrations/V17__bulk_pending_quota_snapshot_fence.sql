-- Refuse a modified V5 guard or bucket binding before replacing the guard body.
-- Flyway executes this DO block and the replacement in one transaction.
do $$
declare
    v_guard oid;
begin
    select p.oid into v_guard
      from pg_catalog.pg_proc p
      join pg_catalog.pg_namespace n on n.oid=p.pronamespace
      join pg_catalog.pg_language lang on lang.oid=p.prolang
     where n.nspname='praxis_bulk' and p.proname='guard_bucket_mutation'
       and pg_catalog.pg_get_function_identity_arguments(p.oid)=''
       and p.proowner=n.nspowner and lang.lanname='plpgsql'
       and p.prorettype='trigger'::pg_catalog.regtype
       and not p.prosecdef and p.provolatile='v' and p.prokind='f'
       and not p.proleakproof and p.proparallel='u'
       and p.proconfig=array['search_path=pg_catalog, pg_temp']::text[]
       and pg_catalog.md5(p.prosrc)='8779437f48965e744f7d2503cbeb9cbf';
    if v_guard is null
       or exists (select 1 from pg_catalog.pg_proc p
                  cross join lateral pg_catalog.aclexplode(coalesce(p.proacl,
                      pg_catalog.acldefault('f',p.proowner))) acl
                  where p.oid=v_guard and acl.privilege_type='EXECUTE'
                    and acl.grantee<>p.proowner)
       or (select count(*) from pg_catalog.pg_trigger t
            where t.tgrelid in (pg_catalog.to_regclass('praxis_bulk.praxis_bulk_deployment_bucket'),
                                pg_catalog.to_regclass('praxis_bulk.praxis_bulk_subject_bucket'))
              and not t.tgisinternal)<>2
       or (select count(*) from pg_catalog.pg_trigger t
            where t.tgfoid=v_guard and not t.tgisinternal)<>2
       or exists (
           select 1 from (values
               ('praxis_bulk.praxis_bulk_deployment_bucket',
                'praxis_bulk_deployment_bucket_guard_mutation'),
               ('praxis_bulk.praxis_bulk_subject_bucket',
                'praxis_bulk_subject_bucket_guard_mutation')
           ) expected(table_name, trigger_name)
           left join pg_catalog.pg_trigger t
             on t.tgrelid=pg_catalog.to_regclass(expected.table_name)
            and t.tgname=expected.trigger_name and not t.tgisinternal
           where t.oid is null or t.tgfoid is distinct from v_guard
              or t.tgtype<>27 or t.tgenabled<>'O'
              or t.tgqual is not null or t.tgnargs<>0
              or t.tgattr<>''::pg_catalog.int2vector
              or pg_catalog.octet_length(t.tgargs)<>0
              or t.tgoldtable is not null or t.tgnewtable is not null)
    then
        raise exception 'bulk V5 bucket guard attestation failed' using errcode='55000';
    end if;
end;
$$;

-- RR proposal admission must create a new bucket tuple version before counting
-- pending allocations. A later RR writer then fails serialization instead of
-- counting an obsolete snapshot after waiting for the bucket lock.
-- All identity changes and DELETE remain forbidden; V5 history is immutable.
create or replace function praxis_bulk.guard_bucket_mutation()
returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
    if TG_OP = 'UPDATE' and new is not distinct from old then
        return new;
    end if;
    raise exception 'bulk quota bucket identity is immutable' using errcode = '55000';
end;
$$;
