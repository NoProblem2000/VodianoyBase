-- Fixture for introspection tests: covers the PostgreSQL 11+ features the introspector has to read.
CREATE SCHEMA sales;
CREATE SCHEMA billing;

CREATE TABLE sales.customers (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      text NOT NULL UNIQUE,
    name       varchar(100) COLLATE "C",
    created_at timestamptz DEFAULT now(),
    score      numeric(5,2) DEFAULT 0 CHECK (score >= 0)
);

CREATE TABLE sales.orders (
    id          serial PRIMARY KEY,
    customer_id bigint REFERENCES sales.customers (id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    amount      numeric(12,2) NOT NULL,
    status      text DEFAULT 'new',
    note        text
);
CREATE INDEX orders_open_status_idx ON sales.orders (status) WHERE status <> 'done';
CREATE INDEX orders_note_lower_idx ON sales.orders (lower(note));

CREATE SEQUENCE sales.invoice_no AS integer START 1000 INCREMENT 5 MAXVALUE 999999 CYCLE;

CREATE TABLE billing.events (
    id         bigint NOT NULL,
    created_on date NOT NULL,
    kind       text NOT NULL,
    payload    jsonb,
    PRIMARY KEY (id, created_on)
) PARTITION BY RANGE (created_on);
CREATE INDEX events_kind_idx ON billing.events (kind);
CREATE TABLE billing.events_2026_01 PARTITION OF billing.events FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE billing.events_2026_q2 PARTITION OF billing.events FOR VALUES FROM ('2026-04-01') TO ('2026-07-01')
    PARTITION BY RANGE (created_on);
CREATE TABLE billing.events_2026_04 PARTITION OF billing.events_2026_q2 FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE billing.events_default PARTITION OF billing.events DEFAULT;
CREATE INDEX events_2026_01_payload_idx ON billing.events_2026_01 (id) WHERE payload IS NOT NULL;

CREATE TABLE billing.reservations (
    id     int PRIMARY KEY,
    during tsrange NOT NULL,
    CONSTRAINT reservations_no_overlap EXCLUDE USING gist (during WITH &&)
);

CREATE VIEW sales.v_orders AS
    SELECT o.id, o.amount, c.email FROM sales.orders o JOIN sales.customers c ON c.id = o.customer_id;
CREATE VIEW sales.v_big_orders AS SELECT * FROM sales.v_orders WHERE amount > 100;
CREATE MATERIALIZED VIEW billing.mv_totals AS
    SELECT customer_id, sum(amount) AS total FROM sales.orders GROUP BY customer_id;
CREATE UNIQUE INDEX mv_totals_customer_idx ON billing.mv_totals (customer_id);

CREATE TABLE public."Mixed Case" ("Select" int, "order" text);

-- must NOT be introspected
CREATE TEMP TABLE scratch (id int);
