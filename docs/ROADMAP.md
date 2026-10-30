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

## S1 — Secure encrypted-PDF import (implemented in source; validation pending)
- Password prompt, local decryption, app-private managed copy, temp-file cleanup, source/derivative relationship.
- Passwords are not persisted; original source is retained; wrong password and cancellation do not create a library entry.
- Never persist passwords. Never replace or rewrite signed original bytes.
- Wrong-password, cancel, unsupported encryption, inaccessible URI, and low-storage handling.
- Exit: protected PDF can be reopened from app-private copy without re-entering its password; original remains unchanged; sensitive data never appears in logs.

## S2 — Digital signature verification (initial implementation; revocation/timestamp work remains)
- Signature discovery, CMS cryptographic validation against original signed byte ranges, and Android system certificate-path evaluation.
- Certificate chain, timestamp and revocation status where available.
- Honest VERIFIED / UNTRUSTED / INVALID / INCOMPLETE / UNSIGNED / UNKNOWN UI.
- Exit: test fixtures for valid, tampered, expired/untrusted, unsigned, and partially validated PDFs; no false green ticks.

## S3 — Searchable content (initial implementation; validation pending)
- PDF embedded-text extraction with page markers, plain-text extraction, SQLite FTS4, and a local query path.
- Snippets and page-aware search results; transactional reindex and stale-index cleanup.
- Exit: query finds text inside PDFs and opens the relevant source/page without reading files on the query path.

## S4 — OCR and durable background processing (initial implementation; validation pending)
- Bundled offline Latin and Devanagari OCR for images and scanned PDF pages, bounded file/page/text processing, and WorkManager retries.
- WorkManager queue, cancellation and idempotent jobs.
- Exit: scanned English/Hindi/Marathi test corpus becomes searchable offline; process death does not create duplicate/stale records.

## S5 — Production hardening
- Expanded filters (folder/category, favourite, signature/encryption/index/availability/size/date), accessibility, privacy review, migration/backup/restore, 1k/10k/100k benchmarks, performance and battery profiling.
- Add folder/tag CRUD, saved searches, page navigation/highlighting, clearable history, export and diagnostics.

## Acceptance targets (measure, do not assume)
- p95 <=50 ms at 10k and <=100 ms at 100k for metadata/full-text query on a named reference device and dataset.
- No OCR or full-file scan on query path; bounded pagination/concurrency; idempotent retries.
- No stale index after committed update/delete; recoverable jobs and verified restore.


## S6 — Initial PDF Editor foundation (implemented in source; validation pending)

- Dedicated full-screen editor workspace launched from a PDF document's More → Edit PDF pages action.
- Load source PDF into a bounded app-cache working copy; preserve the original URI or app-private decrypted source.
- Generate first-page and page-list previews with a bounded thumbnail count.
- Select one or multiple pages.
- Rotate selected pages clockwise or anticlockwise by 90-degree increments.
- Reorder pages using explicit up/down page controls.
- Delete selected pages while preventing an empty PDF.
- Undo/redo page order, rotation and deletion operations (bounded history).
- Save as Copy through Android's document picker; import successful output as a new FileKhoj library item.
- Verify exported page count and page rotations after saving; preserve the source file and never overwrite it.
- Disclose that editing a digitally signed PDF does not retain the original signature's validity.
- Initial constraints: 250 MB maximum source size; 10,000 maximum source pages; thumbnails rendered for the first 48 pages; duplicate-page operation, drag-and-drop reorder, extraction to separate PDFs, merge, image insertion and advanced editing are not part of this S6 initial cut.

### S6 acceptance tests

- Open a multi-page PDF from both a normal content URI and an app-private decrypted copy.
- Rotate individual and multiple pages in both directions; verify resulting orientation in the exported file.
- Reorder first/middle/last pages and verify page identity/order after reopen.
- Delete one and multiple pages; verify all pages cannot be deleted.
- Undo and redo every edit operation.
- Cancel Save As and confirm no new library entry is created.
- Save a copy, verify page count/order/rotation and confirm source bytes remain unchanged.
- Open an edited signed PDF and ensure its signature is not shown as inherited/verified from the source.
- Test malformed, encrypted, large and high-page-count PDFs; fail safely without changing the original.
