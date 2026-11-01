# Persistent Android APK signing

GitHub-hosted runners are fresh machines. Their default Android debug keystore is not a reliable signing identity between workflow runs. FileKhoj update APKs must therefore be signed with one persistent keystore.

## 1. Create the keystore once

On a trusted Windows development PC with JDK 21 installed, run this in PowerShell. Keep the resulting keystore in a secure location and make a second secure backup.

```powershell
keytool -genkeypair -v -keystore shabana-filekhoj-signing.jks -alias filekhoj -keyalg RSA -keysize 2048 -validity 10000
```

Choose strong passwords and keep them safe. Do not commit the JKS file to Git.

## 2. Add GitHub Actions secrets

Open the repository on GitHub and go to **Settings → Secrets and variables → Actions → New repository secret**. Add these four secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64` — Base64 of the complete JKS file. In PowerShell, run `[Convert]::ToBase64String([IO.File]::ReadAllBytes("shabana-filekhoj-signing.jks"))` and copy the output.
- `ANDROID_SIGNING_KEYSTORE_PASSWORD` — the keystore password.
- `ANDROID_SIGNING_KEY_ALIAS` — `filekhoj` (or the alias you selected).
- `ANDROID_SIGNING_KEY_PASSWORD` — the password for that key.

Never paste the keystore or passwords into issues, pull requests, source files, or chat messages.

## 3. Build and download

Run **Actions → Android CI → Run workflow** on `main`. When all four secrets are configured, the workflow signs both the debug and release APKs with this same keystore and publishes the artifact `Shabana-FileKhoj-update-apks`. Keep using APKs from this workflow; do not install a debug APK from an unrelated/local build over them.

If the secrets are missing, CI still builds and tests the project but intentionally does not publish an APK signed with a disposable runner key.

## Important: already-installed app

A new persistent key guarantees that future builds can update one another; it does **not** recover the signing key from an APK produced by an earlier GitHub-hosted run. If the currently installed APK was signed by that run's temporary debug keystore, Android will reject the new persistent-key APK as an update. The private key cannot be reconstructed from the APK's public certificate. Preserve the current app and its data while checking whether the original keystore exists; do not uninstall until the data has been backed up and a migration plan is confirmed.
