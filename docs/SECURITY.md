# Security and document integrity

## Local-only document processing
- Source files are opened through Android's Storage Access Framework.
- Passwords are held only in the active import flow and are not written to Room, preferences, logs, or work input data.
- An encrypted PDF's unlocked derivative is stored under app-private `files/documents/`; Android backup is disabled and external viewers receive temporary URI grants through FileProvider.
- The original source URI is retained and never overwritten. Removing a library entry deletes only app-managed copies located under the private files directory; provider originals are kept.
- Temporary import/index files live in cache and are deleted after processing.

## Digital signatures
- Signature checks run against the original PDF bytes before any unlocked derivative is written.
- The verifier checks CMS signature integrity and attempts to build a signer certificate path to Android's system CA store.
- Statuses are deliberately distinct: VERIFIED, UNTRUSTED, INVALID, INCOMPLETE, UNSIGNED, UNKNOWN.
- The current verifier does not perform certificate revocation checks or trusted timestamp validation. The UI must disclose this; VERIFIED means cryptographic signature and certificate path checks passed under the current policy, not that every issuer-specific authenticity requirement has been met.
- PDFs over the configured verification size limit, signatures covering only an earlier PDF revision, or checks that cannot complete are not shown as fully verified.
- Re-saving a signed PDF invalidates the signature. The decrypted derivative is not the signed original and must not be presented as independently signed.

## Search and OCR
- PDF extraction and OCR execute on-device through WorkManager and bundled OCR models.
- Indexing uses bounded source sizes, page counts, and extracted text length. OCR text and passwords must never be logged.
- Removing a document also removes its extracted-text and FTS entries in a Room transaction.
- Search uses the local SQLite FTS index; there is no cloud upload in the core flow.

## Remaining production hardening
- Add revocation and timestamp validation with explicit online/offline status.
- Add authenticated access / biometric lock if required by product policy.
- Add secure erase expectations for managed copies, export controls, encrypted local backups, and an audited migration/restore test suite.
- Validate malicious PDFs, parser failures, low-storage behavior, URI permission revocation, and large-file memory behavior on real devices.
