-- Commit:  6e4560cb1ac4bcac1950af216028fd79d817c98bc9ea2a48eb2aad4c8f40e9bc
-- Parent:  -
-- Author:  alice
-- Date:    2026-11-06T02:16:33.014973Z
-- Message: Baseline: sales, billing and partitioned events

SET LOCAL check_function_bodies = off;
CREATE SCHEMA IF NOT EXISTS billing;
CREATE SCHEMA IF NOT EXISTS sales;
CREATE TYPE sales.order_priority AS ENUM ('low', 'normal', 'high');
CREATE SEQUENCE sales.invoice_no
    AS integer
    INCREMENT BY 5
    MINVALUE 1
    MAXVALUE 999999
    START WITH 1000
    CACHE 1
    CYCLE;
CREATE SEQUENCE sales.orders_id_seq
    AS integer
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 2147483647
    START WITH 1
    CACHE 1
    NO CYCLE;
CREATE OR REPLACE FUNCTION sales.touch_customer()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    RETURN NEW;
END $function$;
CREATE TABLE billing.events (
    id bigint NOT NULL,
    created_on date NOT NULL,
    kind text NOT NULL,
    payload jsonb
)
    PARTITION BY RANGE (created_on);
CREATE TABLE billing.ledger (
    id bigint NOT NULL,
    account integer NOT NULL,
    amount numeric(12,2),
    memo text
);
CREATE TABLE billing.reservations (
    id integer NOT NULL,
    during tsrange NOT NULL
);
CREATE TABLE public."Mixed Case" (
    "Select" integer,
    "order" text
);
CREATE TABLE sales.customers (
    id bigint GENERATED ALWAYS AS IDENTITY,
    email text NOT NULL,
    name character varying(100) COLLATE "C",
    created_at timestamp with time zone DEFAULT now(),
    score numeric(5,2) DEFAULT 0
);
CREATE TABLE sales.orders (
    id integer DEFAULT nextval('sales.orders_id_seq'::regclass) NOT NULL,
    customer_id bigint,
    amount numeric(12,2) NOT NULL,
    status text DEFAULT 'new'::text,
    note text
);
CREATE TABLE billing.events_2026_01 PARTITION OF billing.events
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE billing.events_2026_q2 PARTITION OF billing.events
    FOR VALUES FROM ('2026-04-01') TO ('2026-07-01')
    PARTITION BY RANGE (created_on);
CREATE TABLE billing.events_default PARTITION OF billing.events
    DEFAULT;
CREATE TABLE billing.events_2026_04 PARTITION OF billing.events_2026_q2
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
ALTER TABLE billing.events ADD CONSTRAINT events_pkey PRIMARY KEY (id, created_on);
ALTER TABLE billing.ledger ADD CONSTRAINT ledger_pkey PRIMARY KEY (id);
ALTER TABLE billing.reservations ADD CONSTRAINT reservations_no_overlap EXCLUDE USING gist (during WITH &&);
ALTER TABLE billing.reservations ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);
ALTER TABLE sales.customers ADD CONSTRAINT customers_email_key UNIQUE (email);
ALTER TABLE sales.customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);
ALTER TABLE sales.customers ADD CONSTRAINT customers_score_check CHECK ((score >= (0)::numeric));
ALTER TABLE sales.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
ALTER TABLE sales.orders ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES sales.customers(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX events_kind_idx ON billing.events USING btree (kind);
CREATE INDEX orders_note_lower_idx ON sales.orders USING btree (lower(note));
CREATE INDEX orders_open_status_idx ON sales.orders USING btree (status) WHERE (status <> 'done'::text);
CREATE INDEX events_2026_01_payload_idx ON billing.events_2026_01 USING btree (id) WHERE (payload IS NOT NULL);
ALTER SEQUENCE sales.orders_id_seq OWNED BY sales.orders.id;
CREATE MATERIALIZED VIEW billing.mv_totals AS
SELECT customer_id,
    sum(amount) AS total
   FROM sales.orders
  GROUP BY customer_id
WITH DATA;
CREATE OR REPLACE VIEW sales.v_orders AS
SELECT o.id,
    o.amount,
    c.email
   FROM sales.orders o
     JOIN sales.customers c ON c.id = o.customer_id;
CREATE OR REPLACE VIEW sales.v_big_orders AS
SELECT id,
    amount,
    email
   FROM sales.v_orders
  WHERE amount > 100::numeric;
CREATE UNIQUE INDEX mv_totals_customer_idx ON billing.mv_totals USING btree (customer_id);
CREATE TRIGGER customers_touch BEFORE UPDATE ON sales.customers FOR EACH ROW EXECUTE FUNCTION sales.touch_customer();
COMMENT ON TABLE sales.customers IS 'Customers of the shop';
