# Shabana FileKhoj

**तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!**  
*Save once. Search anything. Find instantly.*

Android-first, offline-first document vault starter. The repository contains the Android Studio project scaffold, architecture and implementation guidance, build instructions, and branding assets.

> This is a starter template, not a completed document-management application. OCR, indexing, and production features are planned and must be implemented and validated before release.

## Quick start

1. Install Android Studio (stable channel) with its bundled JDK.
2. Clone this repository: `git clone https://github.com/jameerh/Shabana_FileKhoj-.git`
3. Open the cloned `Shabana_FileKhoj-` folder in Android Studio.
4. Allow Gradle sync and install the Android SDK/platform requested by the project.
5. Select the `app` run configuration and an emulator or USB-debugging-enabled device.
6. Run the app. For a command-line build, run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).

See [docs/BUILDING.md](docs/BUILDING.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and [docs/ROADMAP.md](docs/ROADMAP.md).

## Product goals

- Search filenames and extracted PDF/image text from one search field.
- Local SQLite metadata and FTS5 full-text index.
- Background, incremental, recoverable document indexing.
- Page-aware results and OCR for scanned documents.
- Categories, folders, tags, date filters, favourites, and recent files.
- Private, offline-first operation; no cloud dependency for core features.

## Proposed stack

Kotlin, Jetpack Compose, Material 3, Room/SQLite, SQLite FTS5, WorkManager, Android Storage Access Framework, and a replaceable on-device OCR engine. Pin and verify dependency versions during implementation; see [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).

## Repository structure

- `app/` — Android application module and source sets.
- `docs/` — build guide, architecture, database/indexing design, security, testing, and roadmap.
- `design/` — supplied product logo and branding notes.
- `.github/` — contribution guidance and CI workflow.

## Status

Initial starter scaffold. See the roadmap for implementation phases and measurable acceptance criteria.

## License

No license has been selected yet. Until one is added, all rights are reserved by the repository owner. See `LICENSE` status before redistribution.
