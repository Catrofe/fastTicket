--liquibase formatted sql

--changeset fastticket:phase2-001-seat-version
CREATE TABLE seat_version (
    seat_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_seat_version PRIMARY KEY (seat_id),
    CONSTRAINT fk_seat_version_seat FOREIGN KEY (seat_id) REFERENCES seat (id),
    CONSTRAINT ck_seat_version_version CHECK (version >= 0)
);
--rollback DROP TABLE seat_version;
