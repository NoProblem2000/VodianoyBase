-- Commit:  dd6ec3d05f583317c2f1a6c5abd2614a506035a63adbb30697c828cd07cca7d2
-- Parent:  b08a17b72ecca1657971d849d57fe28bfbf241997899e527f1941f640b058e8c
-- Author:  alice
-- Date:    2026-11-06T02:18:11.686806Z
-- Message: Index ledger by account (zero-downtime)

-- UP SQL. Without -- pgvc:step everything runs in one transaction together with the commit.
-- If any statement fails, nothing is applied.

-- pgvc:step
CREATE INDEX CONCURRENTLY ledger_account_idx ON billing.ledger (account);
-- pgvc:step
