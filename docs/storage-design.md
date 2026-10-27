# Filesystem store design (FR-01 .. FR-05, NFR-03, NFR-06)

Package `com.evminsight.storage`. No database (SRS section 2.2).

## Directory structure (inside the controlled application-data directory)
```
store-meta.json              store format version (same envelope + checksum as documents)
LOCK                         OS file lock: one active instance per store (FR-05)
data/<type>/<id>.json        documents; <id>.json.prev = last known-good previous version
safety/pre-restore-<stamp>/  data that a restore replaced (never deleted automatically)
```
Names: `type` = `[a-z][a-z0-9-]*`, `id` = `[A-Za-z0-9][A-Za-z0-9._-]*` (path traversal impossible). Ids are stable; callers use UUIDs.

## Document envelope
`{"type","id","schemaVersion","checksum":"sha256:<hex of canonical data>","data":{...}}`
Money should be stored as decimal strings; BigDecimal numbers are preserved exactly (1500.50 stays 1500.50).

## Guarantees and behaviour
| Situation | Behaviour |
|---|---|
| Save | temp file -> fsync -> copy last valid version to `.prev` -> atomic rename. Any failure throws `IO_FAILURE`; success is never reported early. |
| Crash/exit mid-write | Original untouched; stale `*.tmp` removed at next open (count exposed). |
| Truncated/tampered/misplaced file | `CORRUPT` error, file left as is, never replaced by empty data. `verify()` lists problems; `recoverFromPrevious()` is an explicit action that keeps the damaged file as `.corrupt-<ms>`. |
| Missing metadata but data present | `CORRUPT`: refuses to re-initialise. |
| Newer schema/format | `UNSUPPORTED_VERSION`, data untouched. |
| Older schema | Upgraded in memory by registered migrations (JSON-tree based, unknown fields preserved); `migrateAll()` persists, keeping old file as `.prev`. Missing step -> `UNSUPPORTED_VERSION`. |
| Second instance | `IN_USE` error (OS lock). |
| Backup | ZIP with manifest + SHA-256 per file; refuses damaged data; never overwrites an existing file unless confirmed; written via temp + rename. |
| Restore | Needs explicit confirmation; whole backup validated in a staging folder first (format, checksums, safe paths, types/ids, schema versions); live data moved to `safety/`, never deleted. |
| Permissions | POSIX: directories `rwx------`, files `rw-------`. On Windows: inherits user-profile ACLs (not separately tested). |
| Errors | Messages contain type/id only; no absolute paths or stack traces. |

## Known limitations
- Atomic rename falls back to non-atomic replace if the filesystem lacks atomic move (the `.prev` copy still exists).
- Directory fsync is not performed (Java has no portable API); a power loss right after rename may lose the very last save, but not corrupt the previous one.
- Single-generation `.prev` only; use backups for older history.
- Verified on Linux only (see README for what has/has not been tested).
