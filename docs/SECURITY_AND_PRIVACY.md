# Security and privacy

- Core import, OCR, indexing and search are offline-first.
- Use Storage Access Framework user selection; avoid broad storage permissions without a justified feature.
- Persist URI grants where supported; handle revoked or missing references.
- Keep managed copies and thumbnails in app-private storage.
- Never log document contents, OCR output or sensitive filenames.
- Consider app lock and biometric/device-credential gating.
- Use Android Keystore to protect keys if encrypted storage is implemented.
- Protect OCR text and FTS database as well as originals: a plaintext index can expose encrypted document contents.
- Define encrypted backup/restore before claiming end-to-end protection.
- Distinguish managed file deletion, external files, caches and backups; flash-storage behavior limits secure-erasure guarantees.
- No cloud upload or document-content telemetry without explicit opt-in and disclosure.
