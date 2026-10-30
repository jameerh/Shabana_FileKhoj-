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
- Local user profiles, first-device admin role, admin contact settings and WhatsApp registration notification workflow.
- Custom emerald-and-gold launcher mark matching the Shabana FileKhoj campaign identity.

## Offline and registration model

Document import, indexing and search are designed to operate on the device without a document cloud. Registration profiles are stored locally on the current phone. On a fresh installation, the first profile becomes that device's administrator; subsequent profiles are local users. The admin panel only lists profiles stored on that same phone.

For a multi-device rollout, configure the central admin's WhatsApp number on each user device before registration. A user registration opens WhatsApp with the name, mobile number, locality, app name and automatically generated registration date prefilled. The user must send the message in WhatsApp and confirm in FileKhoj. Android cannot independently verify message delivery. WhatsApp notification requires connectivity, but the document library remains offline-first.

This local profile feature is not server-backed identity authentication, device-to-device account sync, OTP verification or a central admin portal. Do not treat a local Admin role as a secure organization-wide administrator account.

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

The current source includes local document management, Room migrations, FTS4 indexing, background extraction, bundled offline Latin/Devanagari OCR dependencies, secure PDF import handling, PDF signature/CMS verification and the new local profile/WhatsApp notification experience. These features still require validation on representative physical devices and files.

Certificate revocation and trusted timestamp evaluation are not complete. A displayed signature status must not be interpreted as Adobe-equivalent trust validation. OCR/search quality depends on source quality and indexing completion. The profile registry is device-local and does not provide cross-device administration.

## License

No license has been selected yet. Until one is added, all rights are reserved by the repository owner.
