-- Protected proposal input only. This schema deliberately contains neither execution state nor grants.
create table praxis_bulk.praxis_bulk_proposal (
    proposal_id uuid not null,
    namespace_id text not null,
    subject_id text not null,
    resource_key text not null,
    operation_id text not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    fingerprint text not null,
    payload bytea not null,
    constraint praxis_bulk_proposal_pkey primary key (proposal_id),
    constraint praxis_bulk_proposal_namespace_id_nonblank_check
        check (btrim(namespace_id) <> ''),
    constraint praxis_bulk_proposal_subject_id_nonblank_check
        check (btrim(subject_id) <> ''),
    constraint praxis_bulk_proposal_resource_key_nonblank_check
        check (btrim(resource_key) <> ''),
    constraint praxis_bulk_proposal_operation_id_nonblank_check
        check (btrim(operation_id) <> ''),
    constraint praxis_bulk_proposal_valid_window_check
        check (expires_at > created_at),
    constraint praxis_bulk_proposal_fingerprint_format_check
        check (fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_proposal_payload_length_check
        check (octet_length(payload) between 1 and 8388608)
);

create function praxis_bulk.reject_praxis_bulk_proposal_update()
returns trigger
language plpgsql
as $function$
begin
    raise exception 'praxis_bulk.praxis_bulk_proposal is immutable'
        using errcode = '55000';
end;
$function$;

create trigger praxis_bulk_proposal_reject_update
before update on praxis_bulk.praxis_bulk_proposal
for each row
execute function praxis_bulk.reject_praxis_bulk_proposal_update();

-- A controlled runtime role is expected to receive SELECT and INSERT only.
-- This migration intentionally creates no GRANT and exposes no DELETE API.
