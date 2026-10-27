# Change log (SRS section 16)

| ID | Date | Affected reqs | Change | Status |
|----|------|---------------|--------|--------|
| CR-001 | 2026-10-27 | 3.2, 3.3, NFR-05, NFR-10 | Desktop (Tauri) and universal macOS packaging are **deferred** to a later version. Version 1 mid-evaluation delivers the **local web deployment**, run via Docker (works on Apple Silicon and Intel). Reason: 10-day lab timeline; desktop/macOS builds cannot be verified in the development sandbox. Impact: NFR-05/NFR-10 and desktop tests are reported as **not run / out of scope**, never as passed. | Approved by project owner (chat, 2026-10-27) |

Persistence decision: SRS 3.x/FR-01 (versioned filesystem store, no database) is **unchanged**; a hosted DB would violate offline/privacy requirements (NFR-05, NFR-14).
