# Security and privacy

- Core import, OCR, indexing and search are offline-first.
- Use Storage Access Framework user selection; avoid broad storage permissions without a justified feature.
- Persist URI grants where supported; handle revoked or missing references.
- Keep managed copies and thumbnails in app-private storage.
- Never log document contents, OCR output or sensitive filenames.
- Consider app lock and biometric/device-credential gating.
- Use Android Keystore to protect keys if encrypted storage is implemented.
- Protect OCR text and FTS database as well as originals: a plaintext index can expose encrypted document contents.
- Define encrypted backup/restore before claiming end-to-end protection.
- Distinguish managed file deletion, external files, caches and backups; flash-storage behavior limits secure-erasure guarantees.
- No cloud upload or document-content telemetry without explicit opt-in and disclosure.


## Local profile and WhatsApp registration

- The registration feature stores name, mobile number, locality, role, active profile and registration timestamp in app-private SharedPreferences on the current device.
- It does not upload documents or send registration details automatically in the background. The user explicitly opens WhatsApp with a prefilled message and sends it.
- The app asks the user to confirm after returning from WhatsApp. This is a user confirmation only; the app cannot inspect WhatsApp delivery status.
- Admin WhatsApp contact configuration is local to each installation. For a multi-device rollout, provision the correct admin number on each device before user registration.
- The first profile on a new installation is labelled local Admin and later profiles are Users. These are convenience roles for that installation, not server-authorized roles.
- The registration details are personal information. Show the purpose clearly, request only name/mobile/locality, and do not include Aadhaar numbers, document names, OCR content or other file data in the WhatsApp message.
- Do not describe profile selection as secure login. PIN/biometric authentication, encrypted account sync, central approval and cross-device administration are not implemented.
