-- Commit:  b20034896f147c40e29e68312e9ab5061aaff4567f7609488f1a58927f55d8a5
-- Parent:  6e4560cb1ac4bcac1950af216028fd79d817c98bc9ea2a48eb2aad4c8f40e9bc
-- Author:  bob
-- Date:    2026-11-06T02:16:33.646417Z
-- Message: Add customer notes

CREATE TABLE sales.notes (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, customer_id bigint REFERENCES sales.customers(id), body text NOT NULL);
