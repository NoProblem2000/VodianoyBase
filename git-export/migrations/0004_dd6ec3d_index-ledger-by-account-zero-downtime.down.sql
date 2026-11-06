-- Commit:  dd6ec3d05f583317c2f1a6c5abd2614a506035a63adbb30697c828cd07cca7d2
-- Parent:  b08a17b72ecca1657971d849d57fe28bfbf241997899e527f1941f640b058e8c
-- Author:  alice
-- Date:    2026-11-06T02:18:11.686806Z
-- Message: Index ledger by account (zero-downtime)

DROP INDEX billing.ledger_account_idx;
