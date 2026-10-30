# FileKhoj product requirements and phased delivery

## Product principles
- Offline-first, privacy-first, responsive for large libraries, accessible, and recoverable after process death.
- Never silently delete an original source file. "Remove" means remove from FileKhoj unless the user explicitly requests deletion of an app-managed copy.
- Never claim a digital signature is verified unless cryptographic validation and the configured trust checks actually succeeded.
- Preserve signed original bytes. Any decrypted, OCR'd, optimized, or re-saved derivative must be a separate artifact linked to the original.
- Search and bulk actions operate on the currently visible result set; changing the query or filters must not leave hidden records selected.

## Library management (current implementation milestone)
- Import one or many supported files through Android's Storage Access Framework.
- Add/remove entries, toggle favourites, select individual entries, select all visible results, deselect visible results, and clear selection.
- Filter by type and import date; combine filters with filename search.
- Show last three unique searches and allow a tap to repeat a search.
- Preview density options: details, small, medium, large. Image thumbnails use the platform thumbnail API where available; PDFs attempt to render the first page with PdfRenderer.
- Signature state is represented explicitly and defaults to UNKNOWN; a UI badge is not proof of validity.
- Database v1 to v2 migration adds security, availability, and favourite metadata.

## Secure import phases
### S1 — encrypted PDF handling
- Detect encrypted PDFs without sending files off-device.
- Ask for the open password only when required; never log or persist it.
- Validate password before accepting the import.
- Store an app-private decrypted working copy when permitted, and retain the original source for signature verification/audit.
- Do not rewrite a signed original. Any derivative must be marked as a derivative and must not inherit the original's signature status.
- Handle cancellation, incorrect password, unsupported encryption, provider read failure, and low-storage failure.
- Clean up temporary files on success, failure, cancellation, and process recovery.

### S2 — signature verification
- Inspect embedded PDF signature dictionaries and count signatures.
- Verify cryptographic signature bytes against the exact signed byte ranges.
- Validate certificate path and available revocation/timestamp evidence under an explicit trust policy.
- Distinguish VERIFIED, UNTRUSTED, INVALID, INCOMPLETE, UNSIGNED, and UNKNOWN.
- Cache verification time and details, but revalidate stale trust/revocation results when appropriate.
- Preserve original signed bytes and never infer verification from a document name, logo, image, or visible stamp.

### S3 — text extraction and searchable indexing
- Extract embedded PDF text locally, preserving page numbers.
- Add an FTS5-backed index with transactional replacement and snippet/page results.
- OCR scanned PDFs and images through a replaceable on-device engine; expose language/confidence and unsupported states.
- Run heavy work through WorkManager with progress, bounded concurrency, retries, cancellation, and idempotent jobs.
- Index content only on import or changed content, never by scanning every file during a query.

## Search, filters and selection acceptance
- Query and filters combine predictably; filename search is case-insensitive.
- Every bulk-select action affects only currently visible results.
- Search/filter changes clear selection of rows that are no longer visible.
- Bulk delete confirms count and only removes selected library entries; provider originals remain intact.
- Search history stores at most three unique recent queries locally and can be cleared.
- Empty/error/permission-revoked states explain recovery actions.
- Filters planned: file type, import/modified date, category/folder, favourite, availability, index status, encrypted state, signature state, and file size.
- Search planned: filename, extracted content, OCR text, page-aware snippets, typo/prefix handling only after exact search is reliable.

## Reliability and performance
- Use Room migrations; never use destructive migration for user document metadata.
- Use atomic file writes, content hashes, bounded image preview sizes, and lazy/paginated lists for large libraries.
- Keep passwords, extracted text, and document contents out of logs.
- Test duplicate imports, revoked URI access, malformed files, wrong passwords, low storage, deletion while indexing, migration from every released schema, and process death during import.
- Benchmarks are targets, not claims: measure search latency and memory on named devices and representative libraries.
