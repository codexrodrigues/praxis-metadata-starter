-- Protected evaluation evidence. It records immutable evaluation facts for one protected input.
alter table praxis_bulk.praxis_bulk_proposal
    add constraint praxis_bulk_proposal_id_fingerprint_key unique (proposal_id, fingerprint);

create table praxis_bulk.praxis_bulk_evaluation (
    proposal_id uuid not null,
    input_fingerprint text not null,
    evaluation_fingerprint text not null,
    payload bytea not null,
    constraint praxis_bulk_evaluation_pkey primary key (proposal_id),
    constraint praxis_bulk_evaluation_proposal_input_fkey
        foreign key (proposal_id, input_fingerprint)
        references praxis_bulk.praxis_bulk_proposal (proposal_id, fingerprint),
    constraint praxis_bulk_evaluation_fingerprint_format_check
        check (evaluation_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    constraint praxis_bulk_evaluation_payload_length_check
        check (octet_length(payload) between 1 and 8388608)
);

create function praxis_bulk.reject_praxis_bulk_evaluation_update()
returns trigger
language plpgsql
as $function$
begin
    raise exception 'praxis_bulk.praxis_bulk_evaluation is immutable'
        using errcode = '55000';
end;
$function$;

create trigger praxis_bulk_evaluation_reject_update
before update on praxis_bulk.praxis_bulk_evaluation
for each row
execute function praxis_bulk.reject_praxis_bulk_evaluation_update();

-- A controlled runtime role is expected to receive SELECT and INSERT only.
-- This migration intentionally creates no GRANT and exposes no DELETE API.
