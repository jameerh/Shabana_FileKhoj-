# Architecture

## Principles
- Local-first: core import, indexing and search work offline.
- Search never performs OCR; extraction happens during ingestion.
- Incremental and recoverable indexing; no whole-library rebuild for one new document.
- Separate source URI/reference from app-managed file copies.
- Explain result provenance: filename, metadata or page-text match.

## Layers
Presentation (Compose) -> domain use cases -> data repositories (Room/SQLite, storage adapters, text extractors/OCR) -> processing workers (WorkManager) -> Android platform APIs (SAF, Keystore, viewer).

## Ingestion flow
Select/import -> validate URI and MIME -> persist URI permission where supported -> create metadata record -> enqueue extraction -> extract embedded PDF text or OCR image/scanned pages -> normalize -> persist page text -> update FTS index -> mark that content generation indexed. Keep filename metadata searchable if text extraction fails.

## Search flow
Normalize query -> search filenames/metadata and FTS -> apply folder/category/date/type filters -> rank exact filename and phrase hits above weaker matches -> return bounded results with snippets and page numbers. Debounce user typing and cancel obsolete queries. Never open every source file or run OCR on the query path.

## Package direction
`com.shabana.filekhoj`: `data/` (Room/repositories), `domain/` (models/use cases), `indexing/` (queue/workers/extractors/OCR/recovery), `search/` (query parsing/ranking/filters), `storage/` (SAF/managed files), `ui/` (Compose/ViewModels). Add packages as features are implemented rather than creating empty folders.

## Large libraries
Use B-tree indexes for metadata and FTS5 for full text. Store text per page with stable IDs, paginate results, cache thumbnails, bound OCR concurrency, and benchmark real datasets on physical devices. Initial goals (not guarantees): p95 <=50 ms for 10k docs and <=100 ms for 100k docs on a named reference device. Record p50/p95/p99, query plans, index size and device model.
