create table product_snapshot (
    product_id uuid primary key,
    sku varchar(64) not null unique,
    name varchar(160) not null,
    unit_price numeric(19, 2) not null check (unit_price >= 0),
    currency char(3) not null check (currency = 'BRL'),
    active boolean not null,
    updated_at timestamp with time zone not null
);

create table orders (
    order_id uuid primary key,
    customer_id varchar(255) not null,
    status varchar(32) not null,
    currency char(3) not null check (currency = 'BRL'),
    total_amount numeric(19, 2) not null check (total_amount >= 0),
    created_at timestamp with time zone not null
);

create index idx_orders_customer_created_at on orders (customer_id, created_at desc);

create table order_items (
    order_id uuid not null references orders(order_id),
    product_id uuid not null,
    product_name varchar(160) not null,
    unit_price numeric(19, 2) not null check (unit_price >= 0),
    quantity integer not null check (quantity > 0 and quantity <= 10000),
    line_total numeric(19, 2) not null check (line_total >= 0),
    primary key (order_id, product_id)
);

create table order_status_history (
    transition_id uuid primary key,
    order_id uuid not null references orders(order_id),
    sequence_number integer not null,
    status varchar(32) not null,
    occurred_at timestamp with time zone not null,
    unique (order_id, sequence_number)
);

create table idempotency_records (
    customer_id varchar(255) not null,
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    order_id uuid not null references orders(order_id),
    created_at timestamp with time zone not null,
    primary key (customer_id, idempotency_key)
);

create table outbox_messages (
    message_id uuid primary key,
    aggregate_id uuid not null,
    event_type varchar(128) not null,
    event_version integer not null,
    destination varchar(160) not null,
    message_key varchar(255) not null,
    payload text not null,
    created_at timestamp with time zone not null,
    attempt_count integer not null default 0,
    published_at timestamp with time zone null
);

create index idx_outbox_unpublished on outbox_messages (created_at) where published_at is null;

insert into product_snapshot (product_id, sku, name, unit_price, currency, active, updated_at) values
    ('22222222-2222-4222-8222-222222222221', 'OF-NOTEBOOK-STAND', 'Suporte para notebook', 149.90, 'BRL', true, current_timestamp),
    ('22222222-2222-4222-8222-222222222222', 'OF-MECHANICAL-KEYBOARD', 'Teclado mecânico', 399.00, 'BRL', true, current_timestamp),
    ('22222222-2222-4222-8222-222222222223', 'OF-ARCHIVED-MOUSE', 'Mouse descontinuado', 89.90, 'BRL', false, current_timestamp);
