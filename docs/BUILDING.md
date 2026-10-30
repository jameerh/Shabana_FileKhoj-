# Build and run in Android Studio

## Toolchain baseline
This project is aligned to the existing Shabana Android development environment:
- Gradle 8.13
- Android Gradle Plugin 8.8.2
- Kotlin 2.0.21
- KSP 2.0.21-1.0.28
- Android SDK Platform 35 and Build Tools 35.0.0
- JDK 21 runtime for Gradle; app bytecode target remains Java 17
- Minimum Android API 24

These values are the current project baseline, based on the existing Expo project's reported configuration. Reconfirm installed SDK packages locally before downloading anything.

## Requirements
- Android Studio with bundled JDK.
- Android SDK Platform 35 and Build Tools 35.0.0.
- Emulator or Android device with USB debugging enabled.

## Clone
```powershell
git clone https://github.com/jameerh/Shabana_FileKhoj-.git
cd Shabana_FileKhoj-
```

## Android Studio
1. Open Android Studio and select the cloned repository root (contains `settings.gradle.kts`).
2. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select the same bundled JDK 21 used by your working project, if available.
3. Allow Gradle sync to finish.
4. Install Android SDK Platform 35 and Build Tools 35.0.0 from **Tools > SDK Manager** only if missing.
5. Start an emulator or connect a device with USB debugging.
6. Select the `app` run configuration and press **Run**.

## Gradle wrapper
The wrapper JAR and scripts are not committed yet. From the repository root, use an installed Gradle 8.13 to generate them:
```powershell
gradle wrapper --gradle-version 8.13
```
Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/` after generation. Then build using:
```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Never commit `local.properties`, signing keys, secrets, generated build outputs, or customer documents. The scaffold has not been locally built as part of this change; run the build and tests after sync.
