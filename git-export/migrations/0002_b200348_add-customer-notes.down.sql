-- Commit:  b20034896f147c40e29e68312e9ab5061aaff4567f7609488f1a58927f55d8a5
-- Parent:  6e4560cb1ac4bcac1950af216028fd79d817c98bc9ea2a48eb2aad4c8f40e9bc
-- Author:  bob
-- Date:    2026-11-06T02:16:33.646417Z
-- Message: Add customer notes

-- WARNING: Running this script drops table sales.notes together with all its data (16 kB).

ALTER TABLE sales.notes DROP CONSTRAINT notes_customer_id_fkey;
DROP TABLE sales.notes;
