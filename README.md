# Shabana FileKhoj

**तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!**  
*Save once. Search anything. Find instantly.*

Android-first, offline-first document vault starter. The repository contains the Android Studio project scaffold, architecture and implementation guidance, build instructions, and branding notes.

> This is a starter template, not a completed document-management application. OCR, indexing, and production features are planned and must be implemented and validated before release.

## Toolchain baseline

Aligned with the existing Shabana Android development environment:
- Gradle 8.13
- Android Gradle Plugin 8.8.2
- Kotlin 2.0.21 and KSP 2.0.21-1.0.28
- Android SDK Platform 35 / Build Tools 35.0.0
- Gradle runtime JDK 21; application bytecode target Java 17
- Minimum Android API 24

See [docs/BUILDING.md](docs/BUILDING.md) for local setup and Gradle wrapper generation.

## Quick start

1. Install Android Studio with its bundled JDK.
2. Clone this repository: `git clone https://github.com/jameerh/Shabana_FileKhoj-.git`
3. Open the cloned `Shabana_FileKhoj-` folder in Android Studio.
4. Confirm the SDK packages above are installed; install only those that are missing.
5. Allow Gradle sync to finish.
6. Select the `app` run configuration and an emulator or USB-debugging-enabled device.
7. Run the app.

The Gradle wrapper properties are pinned to 8.13. The wrapper scripts and JAR must also be committed before a fresh clone can build from the command line; see [docs/BUILDING.md](docs/BUILDING.md).

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
- `docs/` — build guide, architecture, database/indexing design, security, and roadmap.
- `design/` — branding reference and future app assets.
- `.github/` — contribution guidance and CI workflow.

## Status

Milestone 1 implemented: Room document metadata, multi-file import through Android's system picker, persistent URI permissions where supported, and a filename-filtered local library. Full-text indexing, PDF extraction, OCR, and local device verification remain outstanding. See the roadmap for implementation phases and measurable acceptance criteria.

## License

No license has been selected yet. Until one is added, all rights are reserved by the repository owner. See `LICENSE` status before redistribution.
