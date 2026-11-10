-- Commit:  730e329f304d79c35a109b412d6821a06c4db6624d26d4d9b1ff86b26f28931b
-- Parent:  -
-- Author:  alice
-- Date:    2026-11-10T18:07:00.217273Z
-- Message: Baseline: sales, billing and partitioned events

-- WARNING: Running this script drops table billing.events together with all its data (56 kB).
-- WARNING: Running this script drops partition billing.events_2026_01 together with all its data (24 kB).
-- WARNING: Running this script drops partition billing.events_2026_04 together with all its data (16 kB).
-- WARNING: Running this script drops partition billing.events_2026_q2 together with all its data (16 kB).
-- WARNING: Running this script drops partition billing.events_default together with all its data (16 kB).
-- WARNING: Running this script drops table billing.ledger together with all its data (20 MB / 200k rows).
-- WARNING: Running this script drops table billing.reservations together with all its data (16 kB).
-- WARNING: Running this script drops table public.Mixed Case together with all its data (0 bytes).
-- WARNING: Running this script drops table sales.customers together with all its data (16 kB).
-- WARNING: Running this script drops table sales.orders together with all its data (24 kB).

DROP MATERIALIZED VIEW IF EXISTS billing.mv_totals;
DROP VIEW IF EXISTS sales.v_big_orders;
DROP VIEW IF EXISTS sales.v_orders;
ALTER TABLE sales.orders DROP CONSTRAINT orders_customer_id_fkey;
DROP TABLE billing.events;
-- billing.events_2026_01 is dropped together with its parent billing.events
-- billing.events_2026_04 is dropped together with its parent billing.events_2026_q2
-- billing.events_2026_q2 is dropped together with its parent billing.events
-- billing.events_default is dropped together with its parent billing.events
DROP TABLE billing.ledger;
DROP TABLE billing.reservations;
DROP TABLE public."Mixed Case";
DROP TABLE sales.customers;
DROP TABLE sales.orders;
DROP SEQUENCE IF EXISTS sales.invoice_no;
DROP SEQUENCE IF EXISTS sales.orders_id_seq;
DROP FUNCTION sales.touch_customer();
DROP TYPE sales.order_priority;
DROP SCHEMA billing;
DROP SCHEMA sales;
