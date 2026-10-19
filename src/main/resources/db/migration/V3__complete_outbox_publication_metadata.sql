alter table outbox_messages
    add column claimed_by varchar(128) null,
    add column claimed_at timestamp with time zone null,
    add column last_attempt_at timestamp with time zone null,
    add column last_error varchar(255) null;
