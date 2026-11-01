# Reliability & Quality Audit — Phase Status

This checklist tracks code changes separately from validation evidence. A phase is not considered fully complete until its required CI checks pass. Device-only checks cannot be inferred from a successful APK build.

## Phase 1 — Data integrity and backup (P0)

Implemented on the `phase1/reliability-hardening` branch / PR #4:
- [x] Preserve the previous scheduled backup if promotion of a new archive fails.
- [x] Apply the same safe promotion rule to a downloaded Drive backup.
- [x] Stream restore payloads to private staging files with archive/document/manifest bounds.
- [x] Validate archive format/version, entry names, duplicate/missing/unreferenced entries, payload lengths, and SHA-256 for new-format archives.
- [x] Insert restored document rows in one Room transaction; clean up files when validation or DB commit fails.
- [x] Preserve encrypted-source/unlocked-copy flags in newly generated archives.
- [x] Stage managed files before deleting the database row and restore them if deletion fails.
- [x] Clarify that signature status belongs to the original PDF when the app opens an unlocked derivative.
- [x] Add unit tests for safe backup promotion.
- [ ] Confirm the PR's GitHub Actions build/unit-test result.
- [ ] Run restore failure-injection tests on an Android device with low free storage and a deliberately corrupted ZIP.

## Phase 2 — Search, OCR, PDF tools, accessibility (P1)

Implemented on the `phase2/search-pdf-accessibility` branch / PR #5:
- [x] Normalize search input to Unicode NFC and serialize literal FTS4 terms safely.
- [x] Add unit tests for blank/punctuation-only queries, quoted input, FTS operators, and Marathi Devanagari terms.
- [x] Add an Android integration test that reopens exported PDF output, checks page order/rotation, and verifies source bytes remain unchanged.
- [x] Add meaningful TalkBack labels to PDF page selection/reorder controls and document-card actions.
- [x] Add backup round-trip and payload-tampering instrumentation tests.
- [x] Add an Android-emulator instrumentation test job to CI.
- [ ] Confirm unit-test and emulator-test results on the latest PR head.
- [ ] Test scanned Aadhaar/PAN PDFs, mixed native-text/scanned PDFs, long PDFs, poor-quality images, and Marathi/English mixed content on physical devices.
- [ ] Check large font scaling, TalkBack navigation, and narrow-screen layout on supported Android versions.

## Phase 3 — Security and release readiness

Improved in Phases 1–2:
- [x] Keep PDF passwords out of persistent storage/logging.
- [x] Keep Android application backup disabled.
- [x] Distinguish original PDF signature status from the unlocked derivative in the UI.
- [x] Validate restore payload integrity and bound archive expansion.
- [x] Keep original source documents untouched by PDF editor exports.

Still open:
- [ ] Portable encryption and authentication for exported/Drive backup archives. Current ZIP backups are not encrypted; SHA-256 digests detect corruption but do not authenticate a maliciously modified archive.
- [ ] Certificate revocation and trusted timestamp validation.
- [ ] Decide whether app lock/biometric access control is a product requirement.
- [ ] Configure persistent APK signing secrets and verify upgrade compatibility. A new key cannot update an APK signed with a different old key.
- [ ] Run security checks for malicious PDFs, URI permission loss, low-storage failures, and restored-library rollback on physical devices.

## Phase 4 — Release gate

- [ ] All required unit and instrumentation CI jobs pass on the latest commit.
- [ ] Review the full diff and confirm no user documents, secrets, signing keys, or generated outputs are committed.
- [ ] Install and test the APK on a physical Android device, including import, unlock, search, export, backup/restore, PDF editing, and accessibility.
- [ ] Configure a persistent signing key before publishing upgradeable APK artifacts.
- [ ] Publish the APK artifact only from the exact commit that passed all required checks.

## Known bounded behaviors

- PDF indexing is limited to 2,000 pages and OCR to the first 100 pages per indexing pass. Long scanned PDFs may be marked `OCR_REQUIRED` rather than fully indexed; this is an explicit current limitation, not evidence that all pages were OCRed.
- Signature checks do not yet evaluate revocation or trusted timestamps.
- Backup checksums are integrity checks, not archive authentication or encryption.
- The local profile registry is not a secure cross-device administrator/identity service.
- Instrumentation tests are automated coverage, not a substitute for physical-device testing.
