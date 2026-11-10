-- Commit:  5d51f06965a86072cade9018817940697cb70c94147c656066ffeb25fb03234c
-- Parent:  d55f0e5c4e16b038aeb164f774f68aa3ef3d7d50494ae8f7ba6203c8fdfb73bc
-- Author:  bob
-- Date:    2026-11-10T18:07:00.709792Z
-- Message: Index notes by customer

CREATE INDEX notes_customer_idx ON sales.notes (customer_id);
