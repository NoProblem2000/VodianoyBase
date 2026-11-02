import type { SnapshotBundle } from '../api/types';

/** A parsed .pgsnap.json has formatVersion and schemaSnapshot. */
export function isBundle(value: unknown): value is SnapshotBundle {
  const v = value as Partial<SnapshotBundle> | null;
  return !!v && typeof v === 'object' && typeof v.formatVersion === 'string' && !!v.schemaSnapshot;
}

/**
 * Checks that a file is a .pgsnap.json (valid JSON with formatVersion and schemaSnapshot) and returns its text
 * without a byte-order mark; throws otherwise. The text — not the parsed object — is what goes to the server:
 * JSON.parse + JSON.stringify would round 64-bit numbers (MAXVALUE 9223372036854775807 of a bigint sequence becomes
 * 9223372036854776000, which the server cannot read).
 */
export function readSnapshotText(raw: string): string {
  const text = raw.replace(/^\uFEFF/, '').trim();
  const parsed: unknown = JSON.parse(text);
  if (!isBundle(parsed)) throw new Error('not a .pgsnap.json snapshot');
  return text;
}
