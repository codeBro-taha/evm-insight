# EVM Insight

Project Health & Quality Center — SQE course project. Requirements: see `docs/` (SRS v1.0 + change log).

## Status (chunked delivery)
| Chunk | Content | State |
|-------|---------|-------|
| 1 | Repo, EVM engine, 30 unit tests, reference calcs, CI | done |
| 2 | Filesystem store (versioned, atomic writes, backup) | next |
| 3 | REST API (projects, WBS, baselines) | planned |
| 4 | Reporting, cost entry (both methods), exports | planned |
| 5 | React UI + dashboard | planned |
| 6 | SQE evidence console, RTM, defect log | planned |
| 7 | Docker packaging | planned |

## Run tests (needs JDK 21 + Maven)
    mvn -f backend/pom.xml test

Docker run instructions arrive in chunk 7.
