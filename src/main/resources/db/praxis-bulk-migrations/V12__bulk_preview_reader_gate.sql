-- Owner-only gate for bounded V11 preview reads. The runtime never reads markers directly.
create table praxis_bulk.praxis_bulk_preview_reader_bootstrap (
    bootstrap_version integer primary key check (bootstrap_version = 12),
    phase text not null check (phase in ('PENDING', 'COMPLETE'))
);
insert into praxis_bulk.praxis_bulk_preview_reader_bootstrap values (12, 'PENDING');
revoke all on praxis_bulk.praxis_bulk_preview_reader_bootstrap from public;

create function praxis_bulk.assert_preview_integrity_complete()
returns boolean language plpgsql stable security definer
set search_path = pg_catalog, pg_temp as $$
declare
    v_v11_count bigint;
    v_v12_count bigint;
begin
    select count(*) into v_v11_count
      from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
     where bootstrap_version = 11 and phase = 'COMPLETE';
    select count(*) into v_v12_count
      from praxis_bulk.praxis_bulk_preview_reader_bootstrap
     where bootstrap_version = 12 and phase = 'COMPLETE';
    if v_v11_count <> 1 or v_v12_count <> 1
       or (select count(*) from praxis_bulk.praxis_bulk_preview_integrity_bootstrap) <> 1
       or (select count(*) from praxis_bulk.praxis_bulk_preview_reader_bootstrap) <> 1 then
        raise exception 'bulk preview read gate is incomplete' using errcode = '55000';
    end if;
    return true;
end;
$$;
revoke all on function praxis_bulk.assert_preview_integrity_complete() from public;
