# Build and run in Android Studio

## Requirements
- Android Studio stable, Android SDK Platform 35, JDK 17.
- Android emulator or a device with USB debugging enabled.

## Clone
```bash
git clone https://github.com/jameerh/Shabana_FileKhoj-.git
cd Shabana_FileKhoj-
```

## Android Studio
1. Open Android Studio and select the cloned repository root (contains `settings.gradle.kts`).
2. Set Gradle JDK to 17 if prompted and allow Gradle sync.
3. Install Android SDK Platform 35 from **Tools > SDK Manager** if requested.
4. Start an emulator or connect an Android device with USB debugging.
5. Select the `app` run configuration and press **Run**.

## Command line
After generating and committing the Gradle wrapper:
- Linux/macOS: `./gradlew assembleDebug`
- Windows: `gradlew.bat assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Wrapper note
Wrapper scripts/JAR are not included yet. Install Gradle and run `gradle wrapper --gradle-version 8.9`; commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/` before depending on wrapper commands.

Never commit `local.properties`, signing keys, secrets, or user documents. This repository is a starter scaffold, not the completed document application.
