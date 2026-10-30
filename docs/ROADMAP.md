# Product roadmap

## Implemented source milestone — Library management foundation
- Room metadata, URI-based multi-import, duplicate detection and recent-first list.
- CRUD support for removing entries without deleting provider originals.
- Multi-select, select/deselect visible results, selected-item count and confirmed bulk removal.
- Filename filtering combined with file-type and import-date filters.
- Last three unique searches stored locally.
- Details/small/medium/large preview density; images use Android thumbnails and PDFs attempt a first-page render.
- Favourite metadata and explicit signature-status metadata.
- Room v1-to-v2 migration added.
- **Validation pending:** source changes must compile and run on a device before this milestone is called device-verified.

## S1 — Secure encrypted-PDF import
- Password prompt, local decryption, app-private managed copy, temp-file cleanup, source/derivative relationship.
- Never persist passwords. Never replace or rewrite signed original bytes.
- Wrong-password, cancel, unsupported encryption, inaccessible URI, and low-storage handling.
- Exit: protected PDF can be reopened from app-private copy without re-entering its password; original remains unchanged; sensitive data never appears in logs.

## S2 — Digital signature verification
- Signature discovery and cryptographic validation against original signed byte ranges.
- Certificate chain, timestamp and revocation status where available.
- Honest VERIFIED / UNTRUSTED / INVALID / INCOMPLETE / UNSIGNED / UNKNOWN UI.
- Exit: test fixtures for valid, tampered, expired/untrusted, unsigned, and partially validated PDFs; no false green ticks.

## S3 — Searchable content
- PDF embedded-text extraction with page numbers and SQLite FTS5.
- Snippets and page-aware search results; transactional reindex and stale-index cleanup.
- Exit: query finds text inside PDFs and opens the relevant source/page without reading files on the query path.

## S4 — OCR and durable background processing
- On-device OCR for images and scanned PDFs with language support, bounded concurrency, progress, retries and recovery.
- WorkManager queue, cancellation and idempotent jobs.
- Exit: scanned English/Hindi/Marathi test corpus becomes searchable offline; process death does not create duplicate/stale records.

## S5 — Production hardening
- Expanded filters (folder/category, favourite, signature/encryption/index/availability/size/date), accessibility, privacy review, migration/backup/restore, 1k/10k/100k benchmarks, performance and battery profiling.
- Add folder/tag CRUD, saved searches, page navigation/highlighting, clearable history, export and diagnostics.

## Acceptance targets (measure, do not assume)
- p95 <=50 ms at 10k and <=100 ms at 100k for metadata/full-text query on a named reference device and dataset.
- No OCR or full-file scan on query path; bounded pagination/concurrency; idempotent retries.
- No stale index after committed update/delete; recoverable jobs and verified restore.
