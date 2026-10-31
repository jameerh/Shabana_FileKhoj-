# Shabana FileKhoj

**तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!**  
*Save once. Search anything. Find instantly.*

Shabana FileKhoj is an Android-first, offline-first document library for keeping local references to PDFs, images and supported documents, then finding them by filename or locally indexed text.

## Product experience

- Search-first home screen with Marathi and English product messaging.
- Local document import through Android's Storage Access Framework.
- File type, date, category, favourite and signature filters.
- Recent search history and four preview densities.
- PDF first-page and image previews where the source provider allows them.
- Background text extraction/OCR indexing and local full-text search.
- Password-protected PDF handling with private decrypted working copies; passwords are not stored.
- Signature inspection with explicit status labels and disclosed trust-check limitations.
- Select-one/select-visible, bulk remove, rename/category editing and favourites.
- Initial PDF Editor: page previews, page multi-select, rotate clockwise/anticlockwise by 90°, reorder with page up/down controls, delete selected pages, undo/redo and Save as Copy with output validation.
- Local user profiles, first-device admin role, admin contact settings and WhatsApp registration notification workflow.
- Custom emerald-and-gold launcher mark matching the Shabana FileKhoj campaign identity.

## Offline and registration model

Document import, indexing and search are designed to operate on the device without a document cloud. Registration profiles are stored locally on the current phone. On a fresh installation, the person setting up the phone chooses whether it is the local administrator or a user. User registration requires the central admin WhatsApp number. The admin panel only lists profiles stored on that same phone.

For a multi-device rollout, configure the central admin's WhatsApp number on each user device before registration. A user registration opens WhatsApp with the name, mobile number, locality, app name and automatically generated registration date prefilled. The user must send the message in WhatsApp and confirm in FileKhoj. Android cannot independently verify message delivery. WhatsApp notification requires connectivity, but the document library remains offline-first.

This local profile feature is not server-backed identity authentication, device-to-device account sync, OTP verification or a central admin portal. Do not treat a local Admin role as a secure organization-wide administrator account.

## Google Drive backup setup

FileKhoj keeps its document library local by default. Optional cloud backup uses the Google account selected in **Backup & Restore** and uploads only the generated FileKhoj ZIP archive to the app-owned `ShabanaFileKhoj_Backups` Drive folder.

Before testing Google Drive on a device:

1. In the Google Cloud project, enable the **Google Drive API** and configure the OAuth consent screen.
2. Create an **Android OAuth client** using application ID `in.shabana.filekhoj` and the SHA-1 certificate fingerprint used to sign the installed APK (debug and release certificates have different fingerprints).
3. Make sure the Google account used for testing is allowed by the OAuth consent-screen publishing/test-user configuration.
4. Install the APK, open **Backup & Restore**, connect the Google account, then use **Backup to Drive** or **Restore latest from Drive**.

The app requests only the `drive.file` scope. It creates and accesses its own backup folder and latest ZIP rather than requesting broad access to the user's entire Drive. Cloud backup requires network access; local document import, search and ZIP export remain available offline. Scheduled backups currently remain local-only.

## Toolchain baseline

- Gradle 8.13
- Android Gradle Plugin 8.8.2
- Kotlin 2.0.21 and KSP 2.0.21-1.0.28
- Android SDK Platform 35 / Build Tools 35.0.0
- Gradle runtime JDK 21; application bytecode target Java 17
- Minimum Android API 24

See [docs/BUILDING.md](docs/BUILDING.md) for local setup.

## Quick start

1. Install Android Studio with its bundled JDK.
2. Clone this repository: `git clone https://github.com/jameerh/Shabana_FileKhoj-.git`
3. Open the cloned `Shabana_FileKhoj-` folder in Android Studio.
4. Confirm the SDK packages above are installed.
5. Allow Gradle sync to finish.
6. Select the `app` run configuration and an emulator or USB-debugging-enabled device.
7. Run the app.

## Repository structure

- `app/` — Android application, Compose UI, Room database, document indexing and local registration.
- `docs/` — build guide, architecture, security, product requirements and roadmap.
- `design/` — branding reference.
- `.github/` — contribution guidance and Android CI.

## Implementation status and limitations

The current source includes local document management, Room migrations, FTS4 indexing, background extraction, bundled offline Latin/Devanagari OCR dependencies, secure PDF import handling, PDF signature/CMS verification, local profile/WhatsApp registration and an initial PDF Editor for rotate/reorder/delete page operations. PDF editing has CI validation pending and still requires physical-device testing with representative PDFs.

Certificate revocation and trusted timestamp evaluation are not complete. A displayed signature status must not be interpreted as Adobe-equivalent trust validation. OCR/search quality depends on source quality and indexing completion. The profile registry is device-local and does not provide cross-device administration.

## License

No license has been selected yet. Until one is added, all rights are reserved by the repository owner.
