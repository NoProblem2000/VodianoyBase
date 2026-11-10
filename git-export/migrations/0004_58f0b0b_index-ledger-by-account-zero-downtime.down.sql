-- Commit:  58f0b0bc5f404a91d0750b38e36cba1b1ef7b76eacf286782d2ec50495aed91b
-- Parent:  5d51f06965a86072cade9018817940697cb70c94147c656066ffeb25fb03234c
-- Author:  alice
-- Date:    2026-11-10T18:08:08.377810Z
-- Message: Index ledger by account (zero-downtime)

DROP INDEX billing.ledger_account_idx;
