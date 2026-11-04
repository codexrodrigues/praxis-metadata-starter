-- Opt-in host migration. Execute using the owner role, never automatically at bootstrap.
CREATE TABLE praxis_resource_command_receipts (
    scope_hash varchar(64) PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    response_body text NOT NULL,
    response_version bigint NOT NULL CHECK (response_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
