# Security and document integrity

## Local-only document processing
- Source files are opened through Android's Storage Access Framework.
- Passwords for source PDFs are held only during the active import flow and are not written to Room, preferences, logs, or work input data.
- An encrypted PDF's unlocked derivative is stored under app-private `files/documents/`; Android backup is disabled and external viewers receive temporary URI grants through FileProvider.
- The original source URI is retained and never overwritten. Removing a library entry stages an app-managed private copy before the Room deletion; if the database operation fails, the copy is restored.
- Temporary import/index files live in cache and are deleted after processing.
- The local profile registry is not a server-backed authentication system. A local Admin role is not an organization-wide security boundary.

## Backup and restore integrity
- Restore reads ZIP entries into private staging files instead of holding all document payloads in memory.
- Restore bounds document count, manifest size, per-document payload size, and total expanded payload size. It rejects unsupported versions, duplicate/missing/unreferenced entries, and invalid entry names.
- New backups include a SHA-256 digest and byte count for each document payload. The digest is verified before restore rows are committed.
- Restore inserts the batch in one Room transaction and removes staged/final files if validation or the database transaction fails.
- Scheduled and downloaded backup promotion keeps the previous known-good archive until the new archive is ready.
- **Backup archives are currently not encrypted.** An exported ZIP or Google Drive backup contains readable document bytes. Store it only in a location you trust; do not treat a Drive folder name or account sign-in as end-to-end encryption.
- SHA-256 digests detect accidental corruption but do **not** authenticate the archive: an attacker who can edit the ZIP can also replace the manifest digest. Archive signing/authentication and portable password-based encryption remain production hardening items.
- Restore size limits are deliberate safety limits, not a promise that every very large archive will fit on a device with low free storage.

## Digital signatures
- Signature checks run against the original PDF bytes before any unlocked derivative is written.
- For unlocked copies, the UI labels the signature result as belonging to the original PDF and states that the derivative is not independently signed.
- The verifier checks CMS signature integrity and attempts to build a signer certificate path to Android's system CA store.
- Statuses are deliberately distinct: VERIFIED, UNTRUSTED, INVALID, INCOMPLETE, UNSIGNED, UNKNOWN, EXPIRED, VALID_AT_SIGNING_TIME, and MODIFIED_AFTER_SIGNING.
- The current verifier does not perform certificate revocation checks or trusted timestamp validation. VERIFIED means cryptographic signature and certificate-path checks passed under the current policy; it is not Adobe-equivalent validation and does not establish revocation status.
- PDFs over the configured verification size limit, signatures covering only an earlier PDF revision, or checks that cannot complete are not shown as fully verified.
- Re-saving a signed PDF may invalidate its signature. The editor never overwrites the source PDF.

## Search and OCR
- PDF extraction and OCR execute on-device through WorkManager and bundled OCR models.
- Indexing uses bounded source sizes, page counts, and extracted text length. OCR text and passwords must never be logged.
- Removing a document also removes its extracted-text and FTS entries in a Room transaction.
- Search uses the local SQLite FTS index; there is no cloud upload in the core flow. Google Drive backup uploads the backup archive only when the user explicitly chooses that feature.

## Remaining production hardening
- Add portable password-based encryption and archive authentication for manual/exported/cloud backups.
- Add certificate revocation and trusted timestamp validation with explicit online/offline status.
- Decide whether an app lock / biometric gate is required by product policy, then implement and test it as an explicit security boundary.
- Validate malicious PDFs, parser failures, low-storage behavior, URI permission revocation, and large-file memory behavior on real devices.
- Run restore failure-injection tests, migration tests, and TalkBack/font-scaling checks on supported Android versions.
- Configure persistent APK signing secrets before distributing update-compatible GitHub Actions APKs. A persistent key cannot replace the signing key from an older APK built with a different ephemeral key.
