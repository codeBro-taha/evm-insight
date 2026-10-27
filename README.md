# EVM Insight - Project Health & Quality Center

SQE course project. Requirements baseline: SRS v1.0 (see `docs/`). Web app + desktop app, local filesystem
storage, one authoritative EVM engine in the Spring Boot backend.

## Status

| Part | State |
|---|---|
| EVM calculation engine + reference tests | done, 34 tests |
| Versioned filesystem store: atomic saves, checksums, migrations, lock, backup/restore (27 tests, `docs/storage-design.md`) | done |
| REST API, frontend, evidence console, Docker packaging | next |
| Desktop shell (Tauri) / universal macOS | deferred (CR-001) |

## Run the backend tests (needs only Docker)

    ./scripts/test-backend.sh

## Regenerate the independent reference data (needs Python 3)

    python3 docs/reference/evm_reference.py

## Layout

    backend/    Spring Boot backend and EVM engine (Maven, Java 21)
    docs/       calculation policy, hand calculations, independent reference
    scripts/    repeatable commands
    .github/    CI (tests + coverage kept as build evidence)
