# Reliability & Quality Audit — Phase Status

This checklist separates implementation from validation evidence. A phase is complete only for the scope stated below; physical-device checks and external configuration cannot be inferred from a successful CI build.

## Phase 1 — Data integrity and backup

Implemented in the reliability-hardening changes:
- [x] Preserve the previous scheduled backup if promotion of a new archive fails.
- [x] Apply safe promotion to downloaded Drive backups.
- [x] Stream restore payloads into private staging files with archive/document/manifest bounds.
- [x] Validate archive format/version, entry names, duplicate/missing/unreferenced entries, payload lengths, and SHA-256 for new-format archives.
- [x] Insert restored document rows in one Room transaction; clean up staged files when validation or DB commit fails.
- [x] Preserve encrypted-source/unlocked-copy flags in newly generated archives.
- [x] Stage managed files before deleting the database row and restore them if deletion fails.
- [x] Clarify that signature status belongs to the original PDF when opening an unlocked derivative.
- [x] Add unit tests for safe backup promotion.
- [ ] Run restore failure-injection tests on a physical Android device with low free storage and a deliberately corrupted ZIP.

## Phase 2 — Search, OCR, PDF tools, accessibility

Merged in PR #5:
- [x] Normalize search input to Unicode NFC and serialize literal FTS4 terms safely.
- [x] Add unit tests for blank/punctuation-only queries, quoted input, FTS operators, and Marathi Devanagari terms.
- [x] Add Android integration coverage for exported PDF output, page order/rotation, and source-byte preservation.
- [x] Add TalkBack labels to PDF page selection/reorder controls and document-card actions.
- [x] Add backup round-trip and payload-tampering instrumentation tests.
- [x] Add Android-emulator instrumentation CI.
- [x] GitHub Actions build and instrumentation jobs passed for the Phase 2 PR head.
- [ ] Test scanned Aadhaar/PAN PDFs, mixed native-text/scanned PDFs, long PDFs, poor-quality images, and Marathi/English mixed content on physical devices.
- [ ] Check large font scaling, TalkBack navigation, and narrow-screen layout on supported Android versions.

## Phase 3 — Security boundaries and release readiness

Documented and audited in this phase:
- [x] Record local-only document processing and the limits of device-local profiles.
- [x] Record bounded, staged restore and transactional database insertion.
- [x] Distinguish payload corruption detection from archive authenticity.
- [x] Explicitly disclose that exported and Google Drive ZIP backups are not encrypted.
- [x] Document digital-signature limitations, including absent revocation and trusted-timestamp checks.
- [x] Document persistent APK signing setup and the risk of signing-key mismatch for app updates.
- [x] Establish a phase-by-phase audit checklist and release gates.

Production blockers still open; these are not claimed as fixed by this documentation phase:
- [ ] Implement portable password-based encryption and archive authentication for exported/Drive backups.
- [ ] Implement certificate revocation and trusted timestamp validation with explicit online/offline status.
- [ ] Decide whether app-lock/biometric access control is a product requirement; implement it if required.
- [ ] Configure repository signing secrets and verify the installed app's signing-key/upgrade compatibility before distribution.
- [ ] Run physical-device security checks for malicious PDFs, URI permission loss, low-storage failures, and restore rollback.

## Phase 4 — OCR, document-management, and archive safety

Implemented in the Phase 4 code changes:
- [x] Preserve usable extracted text for long PDFs when the OCR page budget prevents complete coverage.
- [x] Mark incomplete coverage as `PARTIALLY_INDEXED`; reserve `INDEXED` for the configured page-coverage threshold and use `OCR_REQUIRED` when no usable text exists.
- [x] Show understandable index-status labels in document details.
- [x] Bound compressed ZIP size before copying user-selected or Google Drive backup archives into app storage.
- [x] Reject oversized archives during creation and before restore parsing.
- [x] Require SHA-256 and payload byte-count metadata for version 2 archives while retaining version 1 restore compatibility.
- [x] Reset restored PDF signature verdicts to `UNKNOWN` and non-PDFs to `UNSIGNED`; never trust signature claims in an unsigned backup manifest.
- [x] Avoid copying a source PDF's signature verdict to a rewritten/decrypted derivative.
- [x] Add unit and instrumentation tests for partial indexing, archive bounds, missing integrity metadata, payload tampering, signature metadata reset, and encrypted-PDF derivative handling.
- [x] Build and unit-test job passed on the original Phase 4 PR head; a clean main-based PR was prepared to avoid reintroducing old Phase 2 history.
- [x] Phase 4 implementation merged to `main` as `628c1a13cea5cc097d044f5193ea13e828b92885`.
- [x] Build and unit-test job passed on the original Phase 4 PR head.
- [ ] Confirm instrumentation tests pass on the merged Phase 4 commit. The earlier instrumentation attempt failed during emulator boot; a retry is in progress and must finish before this gate is certified.
- [ ] Verify large/scanned PDFs, low-storage archive restore, and provider-specific file handling on physical Android devices.


## Multi-Profile Document Workspaces — Stages 1–6

Implemented on branch `feature/multi-profile-workspaces` (pending CI and review at the time this checklist was updated).

### Stage 1 — Profile foundation
- [x] Add dedicated Room workspace and document-assignment entities, separate from local user-registration profiles.
- [x] Add schema migration that preserves existing document IDs and assigns existing documents to Personal.
- [x] Seed Personal, Shop / Business, Medical, Farm / Agriculture, Property, Family, CSC / Digital Services, Education and Vehicle workspaces.
- [x] Add custom workspace create, rename and delete operations; deleting a workspace preserves documents by assigning them to the default workspace.
- [x] Keep document payloads single-copy while allowing many-to-many workspace assignments.

### Stage 2 — Workspace library
- [x] Add workspace switcher, All profiles view and profile-scoped library filtering.
- [x] Add bulk move/assign without deleting the original file or losing other workspace assignments.
- [x] Support profile-specific category overrides while retaining global document metadata.
- [x] Add a profile manager and assignment counts.
- [ ] Validate migration from real installed v4 database on physical devices and confirm no workspace/profile UI regressions.

### Stage 3 — Low-friction import
- [x] Register FileKhoj as an Android share target for single and multiple files.
- [x] Route shared files into the last-used workspace and copy them into app-managed storage so temporary URI grants do not break future access.
- [x] Add user-selected SAF folder import with recursive traversal, MIME/extension filtering, depth limit and 5,000-file cap.
- [x] Preserve the source file; no silent delete or move from WhatsApp.
- [ ] Validate WhatsApp variants and Android 11+ SAF access on physical devices; inaccessible/private folders must remain gracefully unsupported.

### Stage 4 — Smart organization
- [x] Add local filename/MIME-based profile and category suggestions for common Shop, Farm, Medical, Property, Education, Vehicle and CSC documents.
- [x] Require user action to apply suggestions; do not silently classify sensitive files.
- [x] Add bounded SHA-256 content deduplication for imports up to 512 MB and preserve multiple workspace assignments for duplicate content.
- [x] Add unit tests for built-in workspace definitions and classification rules, plus instrumentation coverage for duplicate content.
- [ ] Verify hash performance and large-file behavior on lower-end devices.

### Stage 5 — Backup and recovery
- [x] Upgrade FileKhoj ZIP manifest to version 3.
- [x] Preserve workspace definitions, per-document workspace assignments, profile-specific categories and reminder metadata.
- [x] Restore document rows, workspace assignments and reminders transactionally after archive validation.
- [x] Keep version 1 and version 2 restore compatibility; retain payload size and SHA-256 checks.
- [ ] Encrypted backup and archive authentication remain separate security work. Current ZIP backups are not encrypted, and SHA-256 metadata is not a signature.

### Stage 6 — Productivity
- [x] Add local due-date reminders and a daily WorkManager scan; notify within seven days of the due date when Android notification permission is granted.
- [x] Add a storage/workspace insights dashboard with document counts, recorded size totals, workspace assignment counts, known duplicate-hash groups and saved reminders.
- [x] Add reminder persistence and backup round-trip coverage.
- [ ] Confirm reminder delivery behavior under Doze, app standby, denied notification permission and OEM battery restrictions.

### Release gates for Multi-Profile Workspaces
- [ ] Latest branch CI build and unit tests pass.
- [ ] Latest branch Android instrumentation tests pass.
- [ ] Real-device tests confirm WhatsApp share, folder import, URI permission loss recovery, profile switching, multi-profile assignment, backup/restore and reminders.
- [ ] Verify existing documents remain accessible after the v4-to-v7 Room migration.
- [ ] Verify a debug-only test APK is clearly distinguished from a persistently signed update-compatible APK.

## Release gate (post-Phase 4)

- [ ] All required unit and instrumentation CI jobs pass on the latest release candidate.
- [ ] Review the full diff and confirm no user documents, secrets, signing keys, or generated outputs are committed.
- [ ] Install and test the APK on a physical Android device: import, unlock, search, export, backup/restore, PDF editing, and accessibility.
- [ ] Configure a persistent signing key before publishing update-compatible APK artifacts.
- [ ] Publish the APK artifact only from the exact commit that passed all required checks.

## Known bounded behaviors

- PDF indexing is limited to 2,000 pages and OCR to the first 100 pages per indexing pass. Long scanned PDFs may be marked `OCR_REQUIRED` or `PARTIALLY_INDEXED` depending on the indexing implementation; this does not mean all pages were OCRed.
- Signature checks do not yet evaluate revocation or trusted timestamps.
- Backup SHA-256 checksums are integrity checks, not archive authentication or encryption.
- The local profile registry is not a secure cross-device administrator/identity service.
- Instrumentation tests are automated coverage, not a substitute for physical-device testing.
