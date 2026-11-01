# Multi-Profile Document Workspaces

## Purpose

One offline-first FileKhoj installation can organize documents for several parts of a user's life or business. Document workspaces are independent from the existing device-local user-registration/admin profiles.

Built-in workspaces:
- Personal
- Shop / Business
- Medical
- Farm / Agriculture
- Property
- Family
- CSC / Digital Services
- Education
- Vehicle

Users can create and rename additional workspaces.

## Data model

- `document_profiles`: workspace metadata and ordering.
- `document_profile_assignments`: many-to-many document/workspace membership and optional category override.
- `documents`: remains the canonical document record. Workspace membership does not duplicate payload bytes.
- `document_reminders`: optional local due-date reminder per document.

Room migrations preserve existing document IDs and assign pre-existing documents to Personal. Removing a workspace reassigns its documents to the default workspace before deleting the workspace.

## Import behavior

### Android Share / WhatsApp
Share one or multiple supported files to Shabana FileKhoj. The app assigns them to the last-used workspace and copies shared content into private app-managed storage where necessary. This prevents an expired temporary share URI permission from making the imported document unusable. The source attachment in WhatsApp is not moved or deleted.

### Folder import
Choose **Import a folder (WhatsApp / device)** and grant access using Android's system folder picker. FileKhoj recursively scans the selected accessible folder, imports supported documents, and assigns them to the current or last-used destination workspace. Scanning is capped at 5,000 files and 12 directory levels. Android may restrict some private or system-managed folders; the app cannot bypass those platform permissions.

### Duplicate detection
For accessible imports up to 512 MB, FileKhoj calculates a SHA-256 content hash. If identical content already exists, it reuses the existing document record and adds the destination workspace assignment. Larger files imported through the normal picker skip content hashing; shared files that must be copied into managed storage are limited to 512 MB. Hashes identify identical bytes; they do not authenticate a document or prove its origin.

### Suggested classification
Local filename and MIME-type rules can suggest a workspace and category. Suggestions are not applied automatically. Users must tap **Apply** or use the bulk move/assign action. Classification is intentionally conservative and does not inspect or upload document content to a cloud service.

## Backup format version 3

New backups include workspace definitions, per-document workspace assignments, profile-specific category overrides and reminder metadata. Restore validates archive structure and payload integrity before committing document rows and workspace assignments in a Room transaction. Version 1 and version 2 archives remain supported.

Important security limits:
- ZIP backups are not encrypted.
- SHA-256 payload checks detect corruption but do not authenticate an unsigned archive against deliberate tampering.
- Workspace profiles are organizational boundaries, not encrypted vaults.
- Certificate revocation and trusted timestamp verification remain separate, unfinished security work.

## Reminders and storage insights

Users can set one local reminder per document for an expiry date, renewal or follow-up. WorkManager checks daily and can notify within seven days of the due date. Notifications require Android notification permission and actual delivery timing is subject to OS scheduling and OEM battery controls.

Storage insights report recorded document sizes and workspace assignment counts. Recorded size totals may differ from actual app disk usage because documents retained by external providers are not necessarily stored in FileKhoj. Duplicate groups only include records with a known content hash.

## Validation checklist

- [ ] Upgrade an existing installation and verify every prior document remains searchable and openable.
- [ ] Share one PDF and multiple files from WhatsApp; verify the private copy survives loss of the original URI grant.
- [ ] Import a user-selected folder and verify the 5,000-file/depth bounds.
- [ ] Assign one document to multiple workspaces, then remove one assignment without deleting the document.
- [ ] Move a document from a single workspace to another and verify at least one assignment remains.
- [ ] Import identical content from different URIs and verify only one document row is created.
- [ ] Export and restore a version 3 backup with custom profiles, category overrides and reminders.
- [ ] Restore version 1 and version 2 archives.
- [ ] Verify notifications with permission granted and denied, and under device idle/battery restrictions.
- [ ] Test on physical devices and different Android versions; emulator success alone is not release certification.
