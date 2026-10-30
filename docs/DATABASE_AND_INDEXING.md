# Database and indexing design

## Implemented in the current scaffold\n- `documents` Room entity with stable generated ID, display name, MIME type, size, source URI, import timestamp, optional source modification timestamp, optional category/hash, and index status.\n- Unique index on `sourceUri` prevents duplicate registration of the same provider URI; indexes support name lookup and recent-first sorting.\n- `DocumentDao` exposes a Flow of recent-first rows and a count Flow.\n- `DocumentRepository` queries standard `OpenableColumns` metadata and imports each selected URI independently so one failed URI does not abort the batch.\n- The UI requests persistable read permission where the provider supports it and stores the URI reference rather than copying source files. A provider can still revoke access; a future availability/revalidation state is required.\n\nThe current import milestone does not extract document text or run OCR. Imported rows remain `NOT_INDEXED`.\n\n## Planned tables
- `documents`: stable ID, display/normalized name, MIME, size, source URI/reference, managed path if any, content hash/version, folder, timestamps, favourite, availability and index status.
- `folders`, `categories`, `document_categories`, `tags`, `document_tags`: organization.
- `document_pages`: page metadata and extraction status.
- `page_text`: extracted text, extractor/version, language and confidence metadata.
- `document_fts`: SQLite FTS5 virtual table keyed to page/document IDs.
- `indexing_jobs`: durable state, attempts, error code and content generation.
- `saved_searches`: saved queries and filters.

## FTS5
Use FTS5 for full text and ordinary SQLite indexes for filters. Verify FTS5 support in SQLite on supported Android versions. Maintain virtual tables with explicit, tested migrations/callbacks. Do not assume Room exposes an FTS annotation for every selected version. Index page-sized text for page-aware results. Prefix indexes improve partial search but increase storage and write costs; benchmark before enabling.

## Idempotent processing
States: DISCOVERED -> IMPORTED -> QUEUED -> EXTRACTING -> INDEXING -> INDEXED. Also support RETRY_PENDING, FAILED, UNSUPPORTED, SOURCE_UNAVAILABLE and NEEDS_REINDEX.

Use stable document ID plus content generation/hash. Stage and validate extraction, then replace page-text and FTS entries transactionally where supported. Otherwise use generation markers and a recovery protocol. Retries must not duplicate records.

Detect possible changes via accessible metadata and content hashes where practical; metadata alone cannot prove unchanged content. On deletion, remove FTS/page-text entries and delete physical files only where the app owns the managed copy or has explicit permission. Version index schema and extractor versions, recover jobs after process death, bound OCR concurrency, and never log OCR text.
