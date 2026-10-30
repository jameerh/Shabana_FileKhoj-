# Dependency and version policy

## Build toolchain baseline
Aligned with the existing Shabana Android project's reported environment:
- Gradle 8.13
- Android Gradle Plugin 8.8.2
- Kotlin 2.0.21
- KSP 2.0.21-1.0.28
- Compile/target SDK 35; Build Tools 35.0.0
- Minimum SDK 24
- Gradle runtime JDK 21; application bytecode target Java 17

## Current application libraries
- Jetpack Compose / Material 3
- AndroidX Core and Activity Compose
- Lifecycle ViewModel
- Room 2.6.1
- WorkManager 2.10.0
- DataStore Preferences
- JUnit and AndroidX instrumentation/UI test libraries

The application deliberately does not depend on Expo or React Native. Keep the current dependencies unless a concrete feature requires another library.

Before adding PDF/OCR dependencies, check Android API compatibility, license, offline behavior, binary size, supported scripts (including Marathi), maintenance status, and instrumentation performance. Prefer replaceable interfaces around extraction/OCR engines.

Versions above are the selected baseline, not a claim that every dependency is the newest available. Confirm compatibility through Gradle sync, unit tests and a debug build on the target environment.
