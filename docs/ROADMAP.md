# Product roadmap

## P0 — Searchable local library
Kotlin/Compose foundation, SAF import, Room schema/migrations, embedded PDF text extraction, FTS5 filename/page-text search, folders/categories, snippets and incremental updates.
Exit: filename and embedded PDF text search works; correctness and latency tests pass.

## P0 — OCR and resilient processing
Image OCR, scanned-PDF rendering/OCR, pluggable OCR adapter, English/Hindi/Marathi script validation, WorkManager queue, retries/recovery/progress, duplicate handling and reindex.
Exit: scanned documents become searchable offline; interrupted processing resumes without duplicate/stale entries.

## P1 — Organization and retrieval
Editable category suggestions, tags, saved searches, date/type/folder filters, favourites, recents, indexing dashboard, page navigation and highlighting.

## P1 — Production hardening
1k/10k/100k profiles, p50/p95/p99 and memory/thermal benchmarks, integrity/migrations/backup/restore, privacy/accessibility/compatibility/security review.

## P2 — Advanced
Voice search, typo tolerance, dates/reference extraction, expiry reminders and optional local semantic search after exact search is proven. Add encrypted sync/shared libraries only if required.

## Acceptance targets (measure, not assume)
p95 <=50 ms at 10k and <=100 ms at 100k on a named reference device/dataset; no OCR/full-file scan on query path; bounded pagination/concurrency; idempotent retries; no stale index after committed update/delete; offline core and verified restore.
