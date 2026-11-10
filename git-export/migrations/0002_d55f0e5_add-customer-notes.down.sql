-- Commit:  d55f0e5c4e16b038aeb164f774f68aa3ef3d7d50494ae8f7ba6203c8fdfb73bc
-- Parent:  730e329f304d79c35a109b412d6821a06c4db6624d26d4d9b1ff86b26f28931b
-- Author:  bob
-- Date:    2026-11-10T18:07:00.642341Z
-- Message: Add customer notes

-- WARNING: Running this script drops table sales.notes together with all its data (8192 bytes).

ALTER TABLE sales.notes DROP CONSTRAINT notes_customer_id_fkey;
DROP TABLE sales.notes;
