--liquibase formatted sql

--changeset fastticket:phase1-001-event
CREATE TABLE event (
    id uuid NOT NULL,
    name text NOT NULL,
    starts_at timestamptz NOT NULL,
    sales_open_at timestamptz NOT NULL,
    status text NOT NULL,
    queue_enabled boolean NOT NULL DEFAULT false,
    admission_rate integer NOT NULL DEFAULT 50,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_event PRIMARY KEY (id)
);
--rollback DROP TABLE event;

--changeset fastticket:phase1-001-sector
CREATE TABLE sector (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    name text NOT NULL,
    kind text NOT NULL,
    capacity integer NOT NULL,
    base_price numeric(12,2) NOT NULL,
    CONSTRAINT pk_sector PRIMARY KEY (id)
);
--rollback DROP TABLE sector;

--changeset fastticket:phase1-001-seat
CREATE TABLE seat (
    id uuid NOT NULL,
    sector_id uuid NOT NULL,
    row_label text NOT NULL,
    seat_number integer NOT NULL,
    CONSTRAINT pk_seat PRIMARY KEY (id)
);
--rollback DROP TABLE seat;

--changeset fastticket:phase1-001-sector-inventory
CREATE TABLE sector_inventory (
    sector_id uuid NOT NULL,
    capacity integer NOT NULL,
    held integer NOT NULL DEFAULT 0,
    sold integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_sector_inventory PRIMARY KEY (sector_id)
);
--rollback DROP TABLE sector_inventory;

--changeset fastticket:phase1-001-lot
CREATE TABLE lot (
    id uuid NOT NULL,
    sector_id uuid NOT NULL,
    number integer NOT NULL,
    qty integer NOT NULL,
    held integer NOT NULL DEFAULT 0,
    sold integer NOT NULL DEFAULT 0,
    price numeric(12,2) NOT NULL,
    starts_at timestamptz NOT NULL,
    CONSTRAINT pk_lot PRIMARY KEY (id)
);
--rollback DROP TABLE lot;

--changeset fastticket:phase1-001-sector-half-quota
CREATE TABLE sector_half_quota (
    sector_id uuid NOT NULL,
    half_cap integer NOT NULL,
    half_held integer NOT NULL DEFAULT 0,
    half_sold integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_sector_half_quota PRIMARY KEY (sector_id)
);
--rollback DROP TABLE sector_half_quota;

--changeset fastticket:phase1-001-buyer-event
CREATE TABLE buyer_event (
    cpf text NOT NULL,
    event_id uuid NOT NULL,
    ticket_count integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_buyer_event PRIMARY KEY (cpf, event_id)
);
--rollback DROP TABLE buyer_event;

--changeset fastticket:phase1-001-hold
CREATE TABLE hold (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    buyer_cpf text NOT NULL,
    status text NOT NULL,
    expires_at timestamptz NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_hold PRIMARY KEY (id)
);
--rollback DROP TABLE hold;

--changeset fastticket:phase1-001-hold-item
CREATE TABLE hold_item (
    id uuid NOT NULL,
    hold_id uuid NOT NULL,
    seat_id uuid,
    sector_id uuid NOT NULL,
    lot_id uuid,
    qty integer NOT NULL DEFAULT 1,
    unit_price numeric(12,2) NOT NULL,
    half_price boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_hold_item PRIMARY KEY (id)
);
--rollback DROP TABLE hold_item;

--changeset fastticket:phase1-001-seat-allocation
CREATE TABLE seat_allocation (
    id uuid NOT NULL,
    seat_id uuid NOT NULL,
    hold_id uuid NOT NULL,
    status text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_seat_allocation PRIMARY KEY (id)
);
--rollback DROP TABLE seat_allocation;

--changeset fastticket:phase1-001-order
CREATE TABLE "order" (
    id uuid NOT NULL,
    hold_id uuid NOT NULL,
    status text NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    confirmed_at timestamptz,
    cancelled_at timestamptz,
    CONSTRAINT pk_order PRIMARY KEY (id)
);
--rollback DROP TABLE "order";

--changeset fastticket:phase1-001-payment
CREATE TABLE payment (
    id uuid NOT NULL,
    hold_id uuid NOT NULL,
    method text NOT NULL,
    amount numeric(12,2) NOT NULL,
    status text NOT NULL,
    gateway_ref text,
    decline_reason text,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment PRIMARY KEY (id)
);
--rollback DROP TABLE payment;

--changeset fastticket:phase1-001-refund
CREATE TABLE refund (
    id uuid NOT NULL,
    payment_id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    status text NOT NULL,
    reason text NOT NULL,
    gateway_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_refund PRIMARY KEY (id)
);
--rollback DROP TABLE refund;

--changeset fastticket:phase1-001-idempotency-key
CREATE TABLE idempotency_key (
    key text NOT NULL,
    scope text NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL,
    response_status integer,
    response_body jsonb,
    resource_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_key PRIMARY KEY (key, scope)
);
--rollback DROP TABLE idempotency_key;
