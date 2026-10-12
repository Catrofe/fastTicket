--liquibase formatted sql

--changeset fastticket:phase1-002-check-constraints
ALTER TABLE event
    ADD CONSTRAINT ck_event_status CHECK (status IN ('ON_SALE', 'CLOSED')),
    ADD CONSTRAINT ck_event_admission_rate CHECK (admission_rate > 0);
ALTER TABLE sector
    ADD CONSTRAINT ck_sector_kind CHECK (kind IN ('NUMBERED', 'GA')),
    ADD CONSTRAINT ck_sector_capacity CHECK (capacity > 0),
    ADD CONSTRAINT ck_sector_base_price CHECK (base_price >= 0);
ALTER TABLE sector_inventory
    ADD CONSTRAINT ck_sector_inventory_counters CHECK (held >= 0 AND sold >= 0 AND held + sold <= capacity);
ALTER TABLE lot
    ADD CONSTRAINT ck_lot_counters CHECK (qty > 0 AND held >= 0 AND sold >= 0 AND held + sold <= qty),
    ADD CONSTRAINT ck_lot_price CHECK (price >= 0);
ALTER TABLE sector_half_quota
    ADD CONSTRAINT ck_sector_half_quota_counters CHECK (half_cap >= 0 AND half_held >= 0 AND half_sold >= 0 AND half_held + half_sold <= half_cap);
ALTER TABLE buyer_event
    ADD CONSTRAINT ck_buyer_event_cpf CHECK (cpf ~ '^[0-9]{11}$'),
    ADD CONSTRAINT ck_buyer_event_ticket_count CHECK (ticket_count BETWEEN 0 AND 6);
ALTER TABLE hold
    ADD CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED', 'CANCELLED')),
    ADD CONSTRAINT ck_hold_total_amount CHECK (total_amount >= 0);
ALTER TABLE hold_item
    ADD CONSTRAINT ck_hold_item_shape CHECK (
        (seat_id IS NOT NULL AND lot_id IS NULL AND qty = 1)
        OR (seat_id IS NULL AND qty > 0)
    ),
    ADD CONSTRAINT ck_hold_item_unit_price CHECK (unit_price >= 0);
ALTER TABLE seat_allocation
    ADD CONSTRAINT ck_seat_allocation_status CHECK (status IN ('HELD', 'SOLD', 'RELEASED'));
ALTER TABLE "order"
    ADD CONSTRAINT ck_order_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    ADD CONSTRAINT ck_order_total_amount CHECK (total_amount >= 0);
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_method CHECK (method IN ('CREDIT', 'DEBIT')),
    ADD CONSTRAINT ck_payment_amount CHECK (amount > 0),
    ADD CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED'));
ALTER TABLE refund
    ADD CONSTRAINT ck_refund_amount CHECK (amount > 0),
    ADD CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'DONE')),
    ADD CONSTRAINT ck_refund_reason CHECK (reason IN ('EXPIRED_AFTER_APPROVAL', 'CANCELLED'));
ALTER TABLE idempotency_key
    ADD CONSTRAINT ck_idempotency_key_status CHECK (status IN ('IN_PROGRESS', 'DONE'));
--rollback ALTER TABLE idempotency_key DROP CONSTRAINT ck_idempotency_key_status;
--rollback ALTER TABLE refund DROP CONSTRAINT ck_refund_amount, DROP CONSTRAINT ck_refund_status, DROP CONSTRAINT ck_refund_reason;
--rollback ALTER TABLE payment DROP CONSTRAINT ck_payment_method, DROP CONSTRAINT ck_payment_amount, DROP CONSTRAINT ck_payment_status;
--rollback ALTER TABLE "order" DROP CONSTRAINT ck_order_status, DROP CONSTRAINT ck_order_total_amount;
--rollback ALTER TABLE seat_allocation DROP CONSTRAINT ck_seat_allocation_status;
--rollback ALTER TABLE hold_item DROP CONSTRAINT ck_hold_item_shape, DROP CONSTRAINT ck_hold_item_unit_price;
--rollback ALTER TABLE hold DROP CONSTRAINT ck_hold_status, DROP CONSTRAINT ck_hold_total_amount;
--rollback ALTER TABLE buyer_event DROP CONSTRAINT ck_buyer_event_cpf, DROP CONSTRAINT ck_buyer_event_ticket_count;
--rollback ALTER TABLE sector_half_quota DROP CONSTRAINT ck_sector_half_quota_counters;
--rollback ALTER TABLE lot DROP CONSTRAINT ck_lot_counters, DROP CONSTRAINT ck_lot_price;
--rollback ALTER TABLE sector_inventory DROP CONSTRAINT ck_sector_inventory_counters;
--rollback ALTER TABLE sector DROP CONSTRAINT ck_sector_kind, DROP CONSTRAINT ck_sector_capacity, DROP CONSTRAINT ck_sector_base_price;
--rollback ALTER TABLE event DROP CONSTRAINT ck_event_status, DROP CONSTRAINT ck_event_admission_rate;

--changeset fastticket:phase1-002-unique-constraints
ALTER TABLE sector ADD CONSTRAINT uq_sector_event_name UNIQUE (event_id, name);
ALTER TABLE seat ADD CONSTRAINT uq_seat_position UNIQUE (sector_id, row_label, seat_number);
ALTER TABLE lot ADD CONSTRAINT uq_lot_sector_number UNIQUE (sector_id, number);
ALTER TABLE "order" ADD CONSTRAINT uq_order_hold UNIQUE (hold_id);
--rollback ALTER TABLE "order" DROP CONSTRAINT uq_order_hold;
--rollback ALTER TABLE lot DROP CONSTRAINT uq_lot_sector_number;
--rollback ALTER TABLE seat DROP CONSTRAINT uq_seat_position;
--rollback ALTER TABLE sector DROP CONSTRAINT uq_sector_event_name;

--changeset fastticket:phase1-002-foreign-keys
ALTER TABLE sector ADD CONSTRAINT fk_sector_event FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE seat ADD CONSTRAINT fk_seat_sector FOREIGN KEY (sector_id) REFERENCES sector (id);
ALTER TABLE sector_inventory ADD CONSTRAINT fk_sector_inventory_sector FOREIGN KEY (sector_id) REFERENCES sector (id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_sector FOREIGN KEY (sector_id) REFERENCES sector (id);
ALTER TABLE sector_half_quota ADD CONSTRAINT fk_sector_half_quota_sector FOREIGN KEY (sector_id) REFERENCES sector (id);
ALTER TABLE buyer_event ADD CONSTRAINT fk_buyer_event_event FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE hold ADD CONSTRAINT fk_hold_event FOREIGN KEY (event_id) REFERENCES event (id);
ALTER TABLE hold ADD CONSTRAINT fk_hold_buyer_event FOREIGN KEY (buyer_cpf, event_id) REFERENCES buyer_event (cpf, event_id);
ALTER TABLE hold_item ADD CONSTRAINT fk_hold_item_hold FOREIGN KEY (hold_id) REFERENCES hold (id);
ALTER TABLE hold_item ADD CONSTRAINT fk_hold_item_seat FOREIGN KEY (seat_id) REFERENCES seat (id);
ALTER TABLE hold_item ADD CONSTRAINT fk_hold_item_sector FOREIGN KEY (sector_id) REFERENCES sector (id);
ALTER TABLE hold_item ADD CONSTRAINT fk_hold_item_lot FOREIGN KEY (lot_id) REFERENCES lot (id);
ALTER TABLE seat_allocation ADD CONSTRAINT fk_seat_allocation_seat FOREIGN KEY (seat_id) REFERENCES seat (id);
ALTER TABLE seat_allocation ADD CONSTRAINT fk_seat_allocation_hold FOREIGN KEY (hold_id) REFERENCES hold (id);
ALTER TABLE "order" ADD CONSTRAINT fk_order_hold FOREIGN KEY (hold_id) REFERENCES hold (id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_hold FOREIGN KEY (hold_id) REFERENCES hold (id);
ALTER TABLE refund ADD CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id);
--rollback ALTER TABLE refund DROP CONSTRAINT fk_refund_payment;
--rollback ALTER TABLE payment DROP CONSTRAINT fk_payment_hold;
--rollback ALTER TABLE "order" DROP CONSTRAINT fk_order_hold;
--rollback ALTER TABLE seat_allocation DROP CONSTRAINT fk_seat_allocation_hold, DROP CONSTRAINT fk_seat_allocation_seat;
--rollback ALTER TABLE hold_item DROP CONSTRAINT fk_hold_item_lot, DROP CONSTRAINT fk_hold_item_sector, DROP CONSTRAINT fk_hold_item_seat, DROP CONSTRAINT fk_hold_item_hold;
--rollback ALTER TABLE hold DROP CONSTRAINT fk_hold_buyer_event, DROP CONSTRAINT fk_hold_event;
--rollback ALTER TABLE buyer_event DROP CONSTRAINT fk_buyer_event_event;
--rollback ALTER TABLE sector_half_quota DROP CONSTRAINT fk_sector_half_quota_sector;
--rollback ALTER TABLE lot DROP CONSTRAINT fk_lot_sector;
--rollback ALTER TABLE sector_inventory DROP CONSTRAINT fk_sector_inventory_sector;
--rollback ALTER TABLE seat DROP CONSTRAINT fk_seat_sector;
--rollback ALTER TABLE sector DROP CONSTRAINT fk_sector_event;

--changeset fastticket:phase1-002-indexes
CREATE UNIQUE INDEX ux_one_active_hold ON hold (buyer_cpf, event_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_hold_sweep ON hold (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_hold_buyer_event ON hold (buyer_cpf, event_id);
CREATE INDEX ix_hold_item_hold ON hold_item (hold_id);
CREATE UNIQUE INDEX ux_seat_active ON seat_allocation (seat_id) WHERE status IN ('HELD', 'SOLD');
CREATE INDEX ix_alloc_hold ON seat_allocation (hold_id);
CREATE UNIQUE INDEX ux_payment_pending_per_hold ON payment (hold_id) WHERE status = 'PENDING';
CREATE INDEX ix_payment_hold ON payment (hold_id);
CREATE INDEX ix_payment_reconcile ON payment (status, created_at);
CREATE UNIQUE INDEX ux_refund_payment ON refund (payment_id);
--rollback DROP INDEX ux_refund_payment;
--rollback DROP INDEX ix_payment_reconcile;
--rollback DROP INDEX ix_payment_hold;
--rollback DROP INDEX ux_payment_pending_per_hold;
--rollback DROP INDEX ix_alloc_hold;
--rollback DROP INDEX ux_seat_active;
--rollback DROP INDEX ix_hold_item_hold;
--rollback DROP INDEX ix_hold_buyer_event;
--rollback DROP INDEX ix_hold_sweep;
--rollback DROP INDEX ux_one_active_hold;
